import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;

public class Controller {

    private int cport;
    private int replication;
    private int timeout;
    private int rebalancePeriod;

    private int state;

    private int connectedStorages;

    private Object storageLock = new Object();
    //Key is the socket port from which you get requests.
    private ConcurrentHashMap<Integer, StorageInfo> storages = new ConcurrentHashMap<Integer, StorageInfo>();

    private Object fileLock = new Object();
    //Key is the name of the file.
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
                                    String input = contactInput.readLine();
                                    String[] message = input.split(" ");

                                    if(input.equals(null))
                                    {
                                        contactInput.close();
                                        break;
                                    }

                                    String command = message[0];

                                    //Join command
                                    //This command is only used by DStores to initialize
                                    if(command.equals(Protocol.JOIN_TOKEN)){
                                        int port = Integer.parseInt(message[1]);

                                        log("Storage " + port + " sent command JOIN from port " + contact.getPort());

                                        boolean storageAlreadyContained = true;

                                        synchronized(storageLock)
                                        {
                                            //Is this even required?
                                            if(!storages.containsKey(contact.getPort()))
                                            {
                                                storageAlreadyContained = false;
                                                storages.put(contact.getPort(), new StorageInfo(contact, contactInput, contactOutput, port));
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
                      
                                        boolean isContained = false;
                                        String storeToPorts = getStoreToPorts();

                                        String[] storagePortInts = storeToPorts.split(" ");

                                        //FROM HERE 
                                        /////////////////////////////////////////////////======================!!!!!!!!!
                                        ArrayList<Integer> storageContactPorts = new ArrayList<Integer>();
                                        
                                        for(String str: storagePortInts)
                                        {
                                            storageContactPorts.add(Integer.parseInt(str));
                                        }

                                        ArrayList<Integer> storageLocalPorts = new ArrayList<Integer>();

                                        synchronized(storageLock)
                                        {
                                            for(Integer intPort: storageContactPorts)
                                            {
                                                storageLocalPorts.add(storages.get(intPort).getPort());
                                            }
                                        }

                                        storeToPorts = "";

                                        for(Integer intPort: storageLocalPorts)
                                        {
                                            storeToPorts += Integer.toString(intPort) + " ";
                                        }

                                        storeToPorts = storeToPorts.substring(0, storeToPorts.length() - 1);
                                        //TO HERE
                                        ////////////////////////////////////===========================!!!!!!!!!!
                                        //Is a fucking temporary fix to the issue:
                                        //Storages now contain their contact port in the key of the hashmap
                                        //This aims to fix the problem that if the client is sending a command
                                        //We have no fucking clue what the actualy socket port of the storages are
                                        //This is fucking horrible and I'd gouge my eyes
                                        //Fix if you;ve got time

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
                                                fileIndex.put(fileName,new FileInfo(fileSize, replication, storeToPorts, storageContactPorts, contact));
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

                                        int currentStoragePort = contact.getPort();

                                        log("Received STORE_ACK from " + currentStoragePort + " for file " + fileName);

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

                                        synchronized(storageLock)
                                        {
                                            storages.get(currentStoragePort).increaseFiles();
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
                                            storagePorts = fileIndex.get(fileName).getStoragesContactPorts();
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

                                        int currentStoragePort = contact.getPort();

                                        log("Received REMOVE_ACK from " + currentStoragePort + " for file " + fileName);

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

                                        synchronized(storageLock)
                                        {
                                            storages.get(currentStoragePort).decreaseFiles();
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
        HashMap<Integer, Integer> portToFileCount = new HashMap<Integer, Integer>();

        synchronized(storageLock)
        {
            keys = storages.keySet();
            for(Integer key: keys)
            {
                Integer numberOfFiles = storages.get(key).getNumberOfFiles();
                portToFileCount.put(key, numberOfFiles);
            }
        }

        //SOLUTION TAKEN FROM https://www.javatpoint.com/how-to-sort-hashmap-by-value
        LinkedList<Entry<Integer, Integer>> list = new LinkedList<Entry<Integer, Integer>>(portToFileCount.entrySet());  

        Collections.sort(list, new Comparator<Entry<Integer, Integer>>()   
		{  
			public int compare(Entry<Integer, Integer> o1, Entry<Integer, Integer> o2)   
			{  
				return o1.getValue().compareTo(o2.getValue());
			}  
		}); 

        int counter = 0;
        for (Entry<Integer, Integer> entry : list)   
		{  
            counter++;
            ports += Integer.toString(entry.getKey()) + " ";
            if(counter == replication)
            {
                break;
            }
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