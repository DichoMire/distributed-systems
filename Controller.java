import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Controller {

    private int cport;
    private int replication;
    private int timeout;
    private int rebalancePeriod;

    private int state;

    private int connectedStorages;

    private Object storageLock = new Object();
    private ConcurrentHashMap<Integer, StorageInfo> storages = new ConcurrentHashMap<Integer, StorageInfo>();

    private Object fileLock = new Object();
    private ConcurrentHashMap<String, FileInfo> fileIndex = new ConcurrentHashMap<String, FileInfo>();

    //Concurrent queue for operations added in queue during rebalance

    public Controller (String [] args)
    {
        this.cport = Integer.parseInt(args[0]);
        this.replication = Integer.parseInt(args[1]);
        this.timeout = Integer.parseInt(args[2]);
        this.rebalancePeriod = Integer.parseInt(args[3]);
        updateStorageCount();

        log("Controller initialized");
        mainSequence();
    }

    public static void main(String [] args)
    {
        Controller mainController = new Controller(args);
    }

    private void mainSequence()
    {
        try
        {
            ServerSocket ss = new ServerSocket(cport);
            for(;;)
            {
                try
                {
                    //Awaits connection
                    log("Awaiting connection with client or Dstore.");
                    Socket contact = ss.accept();
                    log("Connection to port: " + contact.getPort() + " established.");

                    new Thread(() -> {
                        BufferedReader contactInput;
                        PrintWriter contactOutput;

                        try
                        {
                            contactInput = new BufferedReader(new InputStreamReader(contact.getInputStream()));
                            contactOutput = new PrintWriter(new OutputStreamWriter(contact.getOutputStream()),true);
                        } 
                        catch(Exception e)
                        {
                            //No connection found
                            log("Could not setup IO of contact: " + contact.getPort() + " error: " +e);
                            return;
                        }
                        for(;;)
                        { 
                            try
                            {
                                if(contactInput.ready())
                                {
                                    String[] message = contactInput.readLine().split(" ");
                                    String command = message[0];

                                    //Join command
                                    //This command is only used by DStores to initialize
                                    if(command.equals(Protocol.JOIN_TOKEN)){
                                        int port = Integer.parseInt(message[1]);

                                        log("Storage " + port + " sent command JOIN from port " + contact.getPort());

                                        boolean storageAlreadyContained = true;
                                        //Is this even required?
                                        synchronized(storageLock)
                                        {
                                            if(!storages.containsKey(port))
                                            {
                                                storageAlreadyContained = false;
                                                storages.put(port, new StorageInfo(contact, contactInput, contactOutput));
                                            }
                                        }

                                        if(!storageAlreadyContained)
                                        {
                                            log("Adding storage with port: " + Integer.toString(port));
                                            updateStorageCount();
                                        }
                                    }
                                    //Client store command
                                    else if(command.equals(Protocol.STORE_TOKEN))
                                    {
                                        String fileName = message[1];
                                        int fileSize = Integer.parseInt(message[2]);

                                        //Check Invalid input

                                        if(state == States.INSUFFICIENT_REPLICATION)
                                        {
                                            log("Insufficient replication. Command not executed.");
                                            contactOutput.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                                            continue;
                                        }

                                        log("Client " + contact.getPort() + " sent command STORE . FileName: " + fileName + " FileSize: " + fileSize);
    
                                        //Super detailed message
                                        //log("Current fileIndex: " + fileIndex.toString() + " attempting to add: " + fileName + " boolean: " + fileIndex.containsKey(fileName));
                                            
                                        //Preparation
                                        boolean isContained = false;
                                        String storeToPorts = getStoreToPorts();

                                        //If the index already contains the file and the file has not already been removed
                                        //We tell the client the error
                                        synchronized(fileLock)
                                        {
                                            if(fileIndex.containsKey(fileName))
                                            { 
                                                isContained = true;
                                            }
                                            else
                                            {
                                                //Adding new file to the index
                                                fileIndex.put(fileName,new FileInfo(fileSize, replication, storeToPorts, contact));
                                            }
                                        }

                                        if(isContained)
                                        {
                                            log("Client " + contact.getPort() + " attempted to add file: " + fileName + " But it already exists.");
                                            contactOutput.println(Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN);
                                        }
                                        else
                                        {
                                            log("Sending to client: " + contact.getPort() + " STORE_TO command to ports: " + storeToPorts);
                                            contactOutput.println(Protocol.STORE_TO_TOKEN + " " + storeToPorts);
                                        }
                                    }
                                    //Storage STORE_ACK command
                                    else if(command.equals(Protocol.STORE_ACK_TOKEN))
                                    {
                                        String fileName = message[1];
                                        log("Received STORE_ACK from " + contact.getPort() + " for file " + fileName);

                                        boolean storeComplete = false;
                                        Socket modifier = null;

                                        synchronized(fileLock)
                                        {
                                            if(fileIndex.get(fileName).decreaseAcks())
                                            {
                                                storeComplete = true;
                                                fileIndex.get(fileName).setState(States.STORE_COMPLETE);
                                                modifier = fileIndex.get(fileName).getModifier();
                                            }
                                        }

                                        if(storeComplete)
                                        {
                                            OutputStream modifierClientOS = modifier.getOutputStream();
                                            PrintWriter requestOutput = new PrintWriter(new OutputStreamWriter(modifierClientOS),true);
                                            requestOutput.println(Protocol.STORE_COMPLETE_TOKEN);
                                        }
                                    }
                                    //Client load command
                                    else if(command.equals(Protocol.LOAD_TOKEN))
                                    {
                                        if(state == States.INSUFFICIENT_REPLICATION)
                                        {
                                            log("Insufficient replication. Command not executed.");
                                            contactOutput.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                                            continue;
                                        }

                                        String fileName = message[1];

                                        log("Received LOAD from " + contact.getPort() + " for file " + fileName);
                                        
                                        boolean isContained = false;
                                        synchronized(fileLock)
                                        {
                                            if(fileIndex.containsKey(fileName))
                                            {
                                                isContained = true;
                                            }
                                        }

                                        if(!isContained)
                                        {
                                            log("Client " + contact.getPort() + " requested file " + fileName + " " + " but it doesn't exist.");
                                            contactOutput.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
                                            continue;
                                        }

                                        Integer storagePort;
                                        int fileSize;
    
                                        synchronized(fileLock)
                                        {
                                            fileIndex.get(fileName).initializeAvailableStorages();
                                            storagePort = fileIndex.get(fileName).getSingleAvailable();
                                            fileSize = fileIndex.get(fileName).getSize();
                                        }
    
                                        //IMPORTANT CHECKS HERE IF THERE ARE NO STORAGES AT ALL FOR WHATEVER REASON
    
                                        log("Sending to client: " + contact.getPort() + " LOAD_FROM command to port: " + storagePort);
    
                                        contactOutput.println(Protocol.LOAD_FROM_TOKEN + " " + storagePort + " " + fileSize);
                                    }
                                    //Client reload command
                                    else if(command.equals(Protocol.RELOAD_TOKEN))
                                    {
                                        if(state == States.INSUFFICIENT_REPLICATION)
                                        {
                                            log("Insufficient replication. Command not executed.");
                                            contactOutput.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                                            continue;
                                        }

                                        String fileName = message[1];

                                        log("Received RELOAD from " + contact.getPort() + " for file " + fileName);
                                        
                                        Integer storagePort;

                                        int fileSize;

                                        synchronized(fileLock)
                                        {
                                            fileIndex.get(fileName).removeFirstAvailable();
                                            storagePort = fileIndex.get(fileName).getSingleAvailable();
                                            fileSize = fileIndex.get(fileName).getSize();
                                        }

                                        //Removing failed storage from the files storage listing
                                        //fileIndex.get(fileName).removeStorage(failedPort);


                                        //Here we say that there are no more savings of the file.
                                        if(storagePort.equals(null))
                                        {
                                            log("Could not locate any storages that contain file " + fileName + " . Forcibly deleting file from index.");
                                            contactOutput.println(Protocol.ERROR_LOAD_TOKEN);
                                            log("Sending to client: " + contact.getPort() + " ERROR_LOAD.");
                                            
                                            //POTENTIALLY WANT TO CHECK IF THERE IS ISSUE WITH PORTS
                                            //OR DO A REBALANCE OPERATION!!!
                                            //////////////////////////////////////////////////////////
                                            
                                            continue;
                                        }
                                        
                                        log("Sending to client: " + contact.getPort() + " LOAD_FROM command to port: " + storagePort);
                                        contactOutput.println(Protocol.LOAD_FROM_TOKEN + " " + storagePort + " " + fileSize);
                                    }
                                    //Client remove command
                                    else if(command.equals(Protocol.REMOVE_TOKEN))
                                    {
                                        if(state == States.INSUFFICIENT_REPLICATION)
                                        {
                                            log("Insufficient replication. Command not executed.");
                                            contactOutput.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                                            continue;
                                        }

                                        String fileName = message[1];

                                        boolean isContained = false;
                                        synchronized(fileLock)
                                        {
                                            if(fileIndex.containsKey(fileName))
                                            {
                                                isContained = true;
                                            }
                                        }

                                        if(!isContained)
                                        {
                                            log("Client " + contact.getPort() + " requested to remove file " + fileName + " " + " but it doesn't exist.");
                                            contactOutput.println(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
                                            continue;
                                        }

                                        log("Received REMOVE from " + contact.getPort() + " for file " + fileName);

                                        ArrayList<Integer> storagePorts;
                                        ArrayList<Socket> storageSockets = new ArrayList<Socket>();
                                        
                                        synchronized(fileLock)
                                        {
                                            fileIndex.get(fileName).setStateRemove(contact);
                                            storagePorts = fileIndex.get(fileName).getStorages();
                                        }

                                        synchronized(storageLock)
                                        {
                                            for(Integer port: storagePorts)
                                            {
                                                storageSockets.add(storages.get(port).getSocket());
                                            }
                                        }

                                        for(Socket socket: storageSockets)
                                        {
                                            int portNum = socket.getPort();
                                            log("Sending to storage: " + portNum + " REMOVE command for file " + fileName);
                                            new PrintWriter(new OutputStreamWriter(socket.getOutputStream()),true).println(Protocol.REMOVE_TOKEN + " " + fileName);
                                        }
                                    }
                                    else if(command.equals(Protocol.REMOVE_ACK_TOKEN))
                                    {
                                        //REQUIRES CHECK IF FILE EXISTS
                                        String fileName = message[1];

                                        log("Received REMOVE_ACK from " + contact.getPort() + " for file " + fileName);

                                        boolean removeComplete = false;
                                        Socket removeRequester = null;

                                        synchronized(fileLock)
                                        {
                                            if(fileIndex.get(fileName).decreaseAcks())
                                            {
                                                removeComplete = true;
                                                removeRequester = fileIndex.get(fileName).getModifier();
                                                fileIndex.remove(fileName);
                                            }
                                        }

                                        if(removeComplete)
                                        {
                                            PrintWriter requestOutput = new PrintWriter(new OutputStreamWriter(removeRequester.getOutputStream()),true);
                                            log("Sending to client: " + removeRequester.getPort() + " REMOVE_COMPLETE command");
                                            requestOutput.println(Protocol.REMOVE_COMPLETE_TOKEN);
                                        }
                                    }
                                    //Client list command
                                    else if(command.equals(Protocol.LIST_TOKEN))
                                    {
                                        if(state == States.INSUFFICIENT_REPLICATION)
                                        {
                                            log("Insufficient replication. Command not executed.");
                                            contactOutput.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                                            continue;
                                        }

                                        log("Client " + contact.getPort() + " sent command LIST .");

                                        Set<String> keys;
                                        String fileList = "";

                                        synchronized(fileLock)
                                        {
                                            keys = fileIndex.keySet();
                                            for(String key: keys)
                                            {
                                                if(fileIndex.get(key).getState() == States.STORE_COMPLETE)
                                                {
                                                    fileList += key + " ";
                                                }
                                            }
                                        }

                                        if(fileList.length() > 0)
                                        {
                                            fileList = fileList.substring(0, fileList.length()-1);
                                        }
    
                                        contactOutput.println(Protocol.LIST_TOKEN + " " + fileList);
                                    }
                                }
                            }
                            catch(IOException e)
                            {
                                log("Error while reading from IO of contact");
                            }
                        }
                        
                    }).start(); 
                }   
                catch(Exception e)
                {
                    //No connection found
                    log("Could not sonnect the Controller to a contact: "+e);
                }
            }
        }
        catch(Exception e)
        {
            log("Could not connect to Controller Server socket: "+e);
            //Error with Server Socket setup
        }
    }

    private void updateStorageCount()
    {
        synchronized(storageLock)
        {
            connectedStorages = storages.size();
        }

        if(connectedStorages >= replication)
        {
            state = States.SUFFICIENT_REPLICATION;
        }
        else
        {
            state = States.INSUFFICIENT_REPLICATION;
        }
    }

    private String getStoreToPorts()
    {
        String ports = "";
        Set<Integer> keys;

        synchronized(storageLock)
        {
            keys = storages.keySet();
        }

        for(Integer key: keys)
        {
            ports += Integer.toString(key) + " ";
        }
        ports = ports.substring(0, ports.length()-1);
        return ports;
    }

    private void log(String message)
    {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        System.out.println(timestamp + " " + message);
    }
}