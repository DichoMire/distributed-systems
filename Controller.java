import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Controller {

    private int cport;
    private int replication;
    private int timeout;
    private int rebalancePeriod;

    private int state;

    private int connectedStorages;

    private ConcurrentHashMap<Integer, StorageInfo> storages = new ConcurrentHashMap<Integer, StorageInfo>();

    private ConcurrentHashMap<String, FileInfo> fileIndex = new ConcurrentHashMap<String, FileInfo>();

    //Concurrent queue for operations added in queue during rebalance

    //int cport, int replication, int timeout, int rebalancePeriod
    public Controller (String [] args)
    {
        this.cport = Integer.parseInt(args[0]);
        this.replication = Integer.parseInt(args[1]);
        this.timeout = Integer.parseInt(args[2]);
        this.rebalancePeriod = Integer.parseInt(args[3]);
        updateStorageCount();

        System.out.println("Controller initialized");
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
                    System.out.println("Awaiting connection with client or Dstore.");
                    Socket contact = ss.accept();
                    System.out.println("Connection to port: " + contact.getPort() + " established.");

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
                            System.out.println("Could not setup IO of contact: " + contact.getPort() + " error: " +e);
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

                                        System.out.println("Storage " + port + " sent command JOIN from port " + contact.getPort());

                                        //Is this even required?
                                        if(!storages.containsKey(port))
                                        {
                                            System.out.println("Adding storage with port: " + Integer.toString(port));
                                            storages.put(port, new StorageInfo(contact, contactInput, contactOutput));
                                            updateStorageCount();
                                        }
                                    }
                                    //Client store command
                                    else if(command.equals(Protocol.STORE_TOKEN)){
                                        if(state == States.INSUFFICIENT_REPLICATION)
                                        {
                                            System.out.println("Insufficient replication. Command not executed.");
                                            contactOutput.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                                        }
                                        else 
                                        {
                                            String fileName = message[1];
                                            int fileSize = Integer.parseInt(message[2]);
    
                                            System.out.println("Client " + contact.getPort() + " sent command STORE . FileName: " + fileName + " FileSize: " + fileSize);
    
                                            //Super detailed message
                                            //System.out.println("Current fileIndex: " + fileIndex.toString() + " attempting to add: " + fileName + " boolean: " + fileIndex.containsKey(fileName));

                                            //If the index already contains the file and the file has not already been removed
                                            //We tell the client the error
                                            if(fileIndex.containsKey(fileName))
                                            { 
                                                System.out.println("Client " + contact.getPort() + " attempted to add file: " + fileName + " But it already exists.");
                                                contactOutput.println(Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN);
                                            }
                                            //We also need to check if we have Rep factor
                                            else
                                            {
                                                //Adding new file to the index
                                                String storeToPorts = getStoreToPorts();
                                                fileIndex.put(fileName,new FileInfo(fileSize, replication, storeToPorts, contact));
                                                System.out.println("Sending to client: " + contact.getPort() + " STORE_TO command to ports: " + storeToPorts);
                                                contactOutput.println(Protocol.STORE_TO_TOKEN + " " + storeToPorts);
                                            }
                                        }
                                    }
                                    //Client list command
                                    else if(command.equals(Protocol.LIST_TOKEN))
                                    {
                                        if(state == States.INSUFFICIENT_REPLICATION)
                                        {
                                            System.out.println("Insufficient replication. Command not executed.");
                                            contactOutput.println(Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN);
                                        }
                                        else 
                                        {
                                            System.out.println("Client " + contact.getPort() + " sent command LIST .");

                                            Set<String> keys = fileIndex.keySet();
    
                                            String fileList = "";
    
                                            for(String key: keys)
                                            {
                                                if(fileIndex.get(key).getState() == States.STORE_COMPLETE)
                                                {
                                                    fileList += key + " ";
                                                }
                                            }
                                            if(fileList.length() > 0)
                                            {
                                                fileList = fileList.substring(0, fileList.length()-1);
                                            }
    
                                            contactOutput.println(Protocol.LIST_TOKEN + " " + fileList);
                                        }
                                    }
                                    //Storage STORE_ACK command
                                    else if(command.equals(Protocol.STORE_ACK_TOKEN))
                                    {
                                        String fileName = message[1];
                                        if(fileIndex.get(fileName).decreaseAcks())
                                        {
                                            PrintWriter requestOutput = new PrintWriter(new OutputStreamWriter(fileIndex.get(fileName).getModifier().getOutputStream()),true);
                                            requestOutput.println(Protocol.STORE_COMPLETE_TOKEN);
                                            
                                            fileIndex.get(fileName).setState(States.STORE_COMPLETE);
                                            //Maybe close contact socket here?
                                        }
                                    }
                                }
                            }
                            catch(IOException e)
                            {
                                System.out.println("Error while reading from IO of contact");
                            }
                        }
                        
                    }).start(); 
                }   
                catch(Exception e)
                {
                    //No connection found
                    System.out.println("Could not sonnect the Controller to a contact: "+e);
                }
            }
        }
        catch(Exception e)
        {
            System.out.println("Could not connect to Controller Server socket: "+e);
            //Error with Server Socket setup
        }
    }

    private void updateStorageCount()
    {
        connectedStorages = storages.size();

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
        Set<Integer> keys = storages.keySet();
        for(Integer key: keys)
        {
            ports += Integer.toString(key) + " ";
        }
        ports = ports.substring(0, ports.length()-1);
        return ports;
    }
}