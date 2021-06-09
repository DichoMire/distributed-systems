import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedList;
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
        try {
            ControllerLogger.init(Logger.LoggingType.ON_FILE_AND_TERMINAL);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

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
                                String input = contactInput.readLine();
                                if(input == null)
                                {
                                    log("Contact " + contact.getPort() + " sent a null message. Closing socket.");
                                    contactInput.close();
                                    break;
                                }

                                ControllerLogger.getInstance().messageReceived(contact, input);

                                String[] message = input.split(" ");

                                String command = message[0];

                                //Join command
                                //This command is only used by DStores to initialize
                                if(command.equals(Protocol.JOIN_TOKEN)){
                                    int port;

                                    try
                                    {
                                        port = Integer.parseInt(message[1]);
                                    }
                                    catch(Exception e)
                                    {
                                        log("Malformed content of message of command: " + Protocol.JOIN_TOKEN);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[2];
                                        log("Command " + Protocol.JOIN_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

                                    log("Storage " + port + " sent command JOIN from port " + contact.getPort());

                                    synchronized(storageLock)
                                    {
                                        storages.put(contact.getPort(), new StorageInfo(contact, contactInput, contactOutput, port));
                                    }

                                    ControllerLogger.getInstance().dstoreJoined(contact, port);

                                    log("Adding storage with port: " + Integer.toString(port));
                                    updateStorageCount();
                                }
                                //Client store command
                                else if(command.equals(Protocol.STORE_TOKEN))
                                {
                                    String fileName;
                                    int fileSize;

                                    try
                                    {
                                        fileName = message[1];
                                        fileSize = Integer.parseInt(message[2]);
                                    }
                                    catch(Exception e)
                                    {
                                        log("Malformed content of message of command: " + Protocol.STORE_TOKEN);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[3];
                                        log("Command " + Protocol.STORE_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

                                    //Check Invalid input

                                    if(state == States.INSUFFICIENT_REPLICATION)
                                    {
                                        log("Insufficient replication. Command not executed.");
                                        String msg = Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
                                        continue;
                                    }

                                    log("Client " + contact.getPort() + " sent command STORE . FileName: " + fileName + " FileSize: " + fileSize);
                      
                                    boolean isContained = false;
                                    String storeToPorts = getStoreToPorts();

                                    String[] storagePortInts = storeToPorts.split(" ");

                                    //FROM HERE 
                                    /////////////////////////////////////////////////======================!!!!!!!!!
                                    //This gets the ports to which the client needs to store
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
                                            fileIndex.put(fileName,new FileInfo(fileSize, replication, storeToPorts, storageContactPorts, contact, contactOutput));
                                        }
                                    }

                                    if(isContained)
                                    {
                                        log("Client " + contact.getPort() + " attempted to add file: " + fileName + " But it already exists.");
                                        String msg = Protocol.ERROR_FILE_ALREADY_EXISTS_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
                                    }
                                    else
                                    {
                                        log("Sending to client: " + contact.getPort() + " STORE_TO command to ports: " + storeToPorts + " for file " + fileName);
                                        String msg = Protocol.STORE_TO_TOKEN + " " + storeToPorts;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);

                                        //ADD TIMEOUT
                                        long timeoutStart = System.currentTimeMillis();
                                        while(true)
                                        {
                                            if(System.currentTimeMillis() - timeoutStart >= timeout)
                                            {
                                                log(" Timeout in store : " + (System.currentTimeMillis() - timeoutStart));
                                                synchronized(fileLock)
                                                {
                                                    if(!(fileIndex.get(fileName).getState() == States.STORE_COMPLETE))
                                                    { 
                                                        //Safe???
                                                        //Flip me
                                                        fileIndex.remove(fileName);
                                                    }
                                                }
                                                break;
                                            }
                                        }

                                        
                                    }
                                }
                                //Storage STORE_ACK command
                                else if(command.equals(Protocol.STORE_ACK_TOKEN))
                                {
                                    String fileName;

                                    try
                                    {
                                        fileName = message[1];
                                    }
                                    catch(Exception e)
                                    {
                                        log("Malformed content of message of command: " + Protocol.STORE_ACK_TOKEN);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[2];
                                        log("Command " + Protocol.STORE_ACK_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

                                    int currentStoragePort = contact.getPort();

                                    synchronized(fileLock)
                                    {
                                        if(!fileIndex.containsKey(fileName))
                                        {
                                            //File has been removed since the initial store request.
                                            log("Received STORE_ACK from " + currentStoragePort + " for file " + fileName + " but it is missing. Probably has timed out.");
                                            continue;
                                        }
                                    }

                                    try
                                    {
                                        log("Received STORE_ACK from " + currentStoragePort + " for file " + fileName);

                                        boolean storeComplete = false;
                                        Socket modifier = null;
                                        PrintWriter storeRequesterPrint = null;

                                        synchronized(fileLock)
                                        {
                                            if(fileIndex.get(fileName).decreaseAcks())
                                            {
                                                storeComplete = true;
                                                fileIndex.get(fileName).setState(States.STORE_COMPLETE);
                                                modifier = fileIndex.get(fileName).getModifier();
                                                storeRequesterPrint = fileIndex.get(fileName).getModifierPrint();
                                            }
                                        }

                                        //Original increase of numOfFiles in storages
                                        // synchronized(storageLock)
                                        // {
                                        //     storages.get(currentStoragePort).increaseFiles();
                                        // }

                                        if(storeComplete)
                                        {
                                            log("Sending to client: " + modifier.getPort() + " STORE_COMPLETE command");
                                            String msg = Protocol.STORE_COMPLETE_TOKEN;
                                            storeRequesterPrint.println(msg);
                                            ControllerLogger.getInstance().messageSent(modifier, msg);
                                        }
                                    }
                                    catch(Exception e)
                                    {
                                        log("Some error occured during STORE_ACK from storage " + currentStoragePort + " of file " + fileName + " continuing.");
                                    }
                                }
                                //Client load command
                                else if(command.equals(Protocol.LOAD_TOKEN))
                                {
                                    if(state == States.INSUFFICIENT_REPLICATION)
                                    {
                                        log("Insufficient replication. Command not executed.");
                                        String msg = Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
                                        continue;
                                    }

                                    String fileName;

                                    try
                                    {
                                        fileName = message[1];
                                    }
                                    catch(Exception e)
                                    {
                                        log("Malformed content of message of command: " + Protocol.LOAD_TOKEN);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[2];
                                        log("Command " + Protocol.LOAD_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

                                    log("Received LOAD from " + contact.getPort() + " for file " + fileName);
                                    
                                    boolean isContained = false;
                                    synchronized(fileLock)
                                    {
                                        if(fileIndex.get(fileName).getState() == States.STORE_COMPLETE)
                                        {
                                            isContained = true;
                                        }
                                    }

                                    if(!isContained)
                                    {
                                        log("Client " + contact.getPort() + " requested file " + fileName + " " + " but it doesn't exist.");
                                        String msg = Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
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
    
                                    String msg = Protocol.LOAD_FROM_TOKEN + " " + storagePort + " " + fileSize;
                                    contactOutput.println(msg);
                                    ControllerLogger.getInstance().messageSent(contact, msg);
                                }
                                //Client reload command
                                else if(command.equals(Protocol.RELOAD_TOKEN))
                                {
                                    if(state == States.INSUFFICIENT_REPLICATION)
                                    {
                                        log("Insufficient replication. Command not executed.");
                                        String msg = Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
                                        continue;
                                    }

                                    String fileName;

                                    try
                                    {
                                        fileName = message[1];
                                    }
                                    catch(Exception e)
                                    {
                                        log("Malformed content of message of command: " + Protocol.RELOAD_TOKEN);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[2];
                                        log("Command " + Protocol.RELOAD_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

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
                                        String msg = Protocol.ERROR_LOAD_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
                                        log("Sending to client: " + contact.getPort() + " ERROR_LOAD.");
                                        
                                        //POTENTIALLY WANT TO CHECK IF THERE IS ISSUE WITH PORTS
                                        //OR DO A REBALANCE OPERATION!!!
                                        //////////////////////////////////////////////////////////
                                        
                                        continue;
                                    }
                                    
                                    log("Sending to client: " + contact.getPort() + " LOAD_FROM command to port: " + storagePort);
                                    String msg = Protocol.LOAD_FROM_TOKEN + " " + storagePort + " " + fileSize;
                                    contactOutput.println(msg);
                                    ControllerLogger.getInstance().messageSent(contact, msg);
                                }
                                //Client remove command
                                else if(command.equals(Protocol.REMOVE_TOKEN))
                                {
                                    if(state == States.INSUFFICIENT_REPLICATION)
                                    {
                                        log("Insufficient replication. Command not executed.");
                                        String msg = Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
                                        continue;
                                    }

                                    String fileName;

                                    try
                                    {
                                        fileName = message[1];
                                    }
                                    catch(Exception e)
                                    {
                                        log("Malformed content of message of command: " + Protocol.REMOVE_TOKEN);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[2];
                                        log("Command " + Protocol.REMOVE_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

                                    boolean isContained = false;
                                    synchronized(fileLock)
                                    {
                                        try
                                        {
                                            if(fileIndex.get(fileName).getState() == States.STORE_COMPLETE)
                                            {
                                                isContained = true;
                                            }
                                        }
                                        catch(Exception ignored){}
                                    }

                                    if(!isContained)
                                    {
                                        log("Client " + contact.getPort() + " requested to remove file " + fileName + " " + " but it doesn't exist.");
                                        String msg = Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
                                        continue;
                                    }

                                    ArrayList<Integer> storagePorts;
                                    
                                    synchronized(fileLock)
                                    {
                                        fileIndex.get(fileName).setStateRemove(contact, contactOutput);
                                        storagePorts = fileIndex.get(fileName).getStoragesContactPorts();
                                    }

                                    ArrayList<Socket> storageSockets = new ArrayList<Socket>();
                                    ArrayList<PrintWriter> storagePrinters = new ArrayList<PrintWriter>();

                                    synchronized(storageLock)
                                    {
                                        for(Integer port: storagePorts)
                                        {
                                            storageSockets.add(storages.get(port).getSocket());
                                            storagePrinters.add(storages.get(port).getOutput());
                                        }
                                    }

                                    log("Received REMOVE from " + contact.getPort() + " for file " + fileName);

                                    for(int i = 0; ;i++)
                                    {
                                        try
                                        {
                                            Socket socket = storageSockets.get(i);
                                            PrintWriter pr = storagePrinters.get(i);
                                            int portNum = socket.getPort();
                                            log("Sending to storage: " + portNum + " REMOVE command for file " + fileName);
                                            String msg = Protocol.REMOVE_TOKEN + " " + fileName;
                                            pr.println(msg);
                                            ControllerLogger.getInstance().messageSent(socket, msg);
                                        }
                                        catch(Exception e)
                                        {
                                            break;
                                        }
                                    }

                                    //Remove complete; start timeout and prep
                                    long timeoutStart = System.currentTimeMillis();
                                    while(true)
                                    {
                                        if(System.currentTimeMillis() - timeoutStart >= timeout)
                                        {
                                            log(" Timeout in remove : " + (System.currentTimeMillis() - timeoutStart));
                                            synchronized(fileLock)
                                            {
                                                if(fileIndex.containsKey(fileName))
                                                {
                                                    //REMOVING FILE IF REMOVE COMMAND TIMES OUT. LACKS LOGGING!
                                                    fileIndex.remove(fileName);
                                                    //Needs polishing
                                                }
                                            }
                                            break;
                                        }
                                    }
                                }
                                //Storage ERROR file command
                                else if(command.equals(Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN))
                                {
                                    //REQUIRES CHECK IF FILE EXISTS
                                    String fileName;

                                    try
                                    {
                                        fileName = message[1];
                                    }
                                    catch(Exception e)
                                    {
                                        log("Malformed content of message of command: " + Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[2];
                                        log("Command " + Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

                                    int currentStoragePort = contact.getPort();

                                    synchronized(fileLock)
                                    {
                                        if(!fileIndex.containsKey(fileName))
                                        {
                                            //File has been removed since the initial store request.
                                            log("Received ERROR_FILE_DOES_NOT_EXIST_TOKEN from " + currentStoragePort + " for file " + fileName + " but it is missing. Probably has timed out.");
                                            continue;
                                        }
                                    }

                                    try
                                    {
                                        int fileState;
                                        synchronized(fileLock)
                                        {
                                            fileState = fileIndex.get(fileName).getState();
                                        }

                                        if(fileState == States.REMOVE_IN_PROGRESS)
                                        {
                                            boolean removeComplete = false;
                                            Socket removeRequester = null;
                                            PrintWriter removeRequesterPrint = null;
        
                                            synchronized(fileLock)
                                            {
                                                if(fileIndex.get(fileName).decreaseAcks())
                                                {
                                                    removeComplete = true;
                                                    removeRequester = fileIndex.get(fileName).getModifier();
                                                    removeRequesterPrint = fileIndex.get(fileName).getModifierPrint();
                                                    fileIndex.remove(fileName);
                                                }
                                            }
    
                                            synchronized(storageLock)
                                            {
                                                storages.get(currentStoragePort).decreaseFiles();
                                            }
    
                                            if(removeComplete)
                                            {
                                                log("Sending to client: " + removeRequester.getPort() + " REMOVE_COMPLETE command");
                                                String msg = Protocol.REMOVE_COMPLETE_TOKEN;
                                                removeRequesterPrint.println(msg);
                                                ControllerLogger.getInstance().messageSent(removeRequester, msg);
                                            }
                                        }
                                        else if(fileState == States.STORE_IN_PROGRESS)
                                        {
                                            boolean storeComplete = false;
                                            Socket modifier = null;
                                            PrintWriter storeRequesterPrint = null;

                                            synchronized(fileLock)
                                            {
                                                if(fileIndex.get(fileName).decreaseAcks())
                                                {
                                                    storeComplete = true;
                                                    fileIndex.get(fileName).setState(States.STORE_COMPLETE);
                                                    modifier = fileIndex.get(fileName).getModifier();
                                                    storeRequesterPrint = fileIndex.get(fileName).getModifierPrint();
                                                }
                                            }

                                            //Original increase of numOfFiles in storages
                                            // synchronized(storageLock)
                                            // {
                                            //     storages.get(currentStoragePort).increaseFiles();
                                            // }

                                            if(storeComplete)
                                            {
                                                log("Sending to client: " + modifier.getPort() + " STORE_COMPLETE command");
                                                String msg = Protocol.STORE_COMPLETE_TOKEN;
                                                storeRequesterPrint.println(msg);
                                                ControllerLogger.getInstance().messageSent(modifier, msg);
                                            }
                                        }
                                    }
                                    catch(Exception e)
                                    {
                                        log("Some error occured during ERROR_FILE_DOES_NOT_EXIST_TOKEN from storage " + currentStoragePort + " of file " + fileName + " continuing.");
                                    }
                                }
                                else if(command.equals(Protocol.REMOVE_ACK_TOKEN))
                                {
                                    //REQUIRES CHECK IF FILE EXISTS
                                    String fileName;
                                    
                                    try
                                    {
                                        fileName = message[1];
                                    }
                                    catch(Exception e)
                                    {
                                        log("Malformed content of message of command: " + Protocol.REMOVE_ACK_TOKEN);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[2];
                                        log("Command " + Protocol.REMOVE_ACK_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

                                    int currentStoragePort = contact.getPort();

                                    synchronized(fileLock)
                                    {
                                        if(!fileIndex.containsKey(fileName))
                                        {
                                            //File has been removed since the initial store request.
                                            log("Received REMOVE_ACK from " + currentStoragePort + " for file " + fileName + " but it is missing. Probably has timed out.");
                                            continue;
                                        }
                                    }

                                    try
                                    {
                                        log("Received REMOVE_ACK from " + currentStoragePort + " for file " + fileName);

                                        boolean removeComplete = false;
                                        Socket removeRequester = null;
                                        PrintWriter removeRequesterPrint = null;
    
                                        synchronized(fileLock)
                                        {
                                            if(fileIndex.get(fileName).decreaseAcks())
                                            {
                                                removeComplete = true;
                                                removeRequester = fileIndex.get(fileName).getModifier();
                                                removeRequesterPrint = fileIndex.get(fileName).getModifierPrint();
                                                fileIndex.remove(fileName);
                                            }
                                        }
    
                                        synchronized(storageLock)
                                        {
                                            storages.get(currentStoragePort).decreaseFiles();
                                        }
    
                                        if(removeComplete)
                                        {
                                            log("Sending to client: " + removeRequester.getPort() + " REMOVE_COMPLETE command");
                                            String msg = Protocol.REMOVE_COMPLETE_TOKEN;
                                            removeRequesterPrint.println(msg);
                                            ControllerLogger.getInstance().messageSent(removeRequester, msg);
                                        }
                                    }
                                    catch(Exception e)
                                    {
                                        log("Some error occured during REMOVE_ACK from storage " + currentStoragePort + " of file " + fileName + " continuing.");
                                    }
                                }
                                //Client list command
                                else if(command.equals(Protocol.LIST_TOKEN))
                                {
                                    if(state == States.INSUFFICIENT_REPLICATION)
                                    {
                                        log("Insufficient replication. Command not executed.");
                                        String msg = Protocol.ERROR_NOT_ENOUGH_DSTORES_TOKEN;
                                        contactOutput.println(msg);
                                        ControllerLogger.getInstance().messageSent(contact, msg);
                                        continue;
                                    }

                                    try
                                    {
                                        String test = message[1];
                                        log("Command " + Protocol.LIST_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

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
    
                                    log("Sending to client: " + contact.getPort() + " LIST command with contents " + fileList);
                                    String msg = Protocol.LIST_TOKEN + " " + fileList;
                                    contactOutput.println(msg);
                                    ControllerLogger.getInstance().messageSent(contact, msg);
                                }
                                else
                                {
                                    log("Malformed command received. Continuing.");
                                }
                            }
                            catch(IOException e)
                            {
                                log("Error while reading from IO of contact. Closing contact.");
                                break;
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

        list = new LinkedList<Entry<Integer, Integer>>(list.subList(0, replication));

        synchronized(storageLock)
        {
            for (Entry<Integer, Integer> entry : list)   
		    {  
                storages.get(entry.getKey()).increaseFiles();
		    }
        }

        for (Entry<Integer, Integer> entry : list)   
		{  
            ports += Integer.toString(entry.getKey()) + " ";
		}

        ports = ports.substring(0, ports.length()-1);
        log("Algorithm deemed the following ports for storage: " + ports);
        return ports;
    }

    private void log(String message)
    {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        System.out.println(timestamp + " " + message);
    }
}