import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class Dstore {
    
    private int port;
    private int cport;
    private int timeout;
    private String fileFolder;

    private Object fileLock = new Object();
    //Hashmap with clients
    private ConcurrentHashMap<String, Integer> fileIndex;

    public Dstore (String[] args)
    {
        this.port = Integer.parseInt(args[0]);
        this.cport = Integer.parseInt(args[1]);
        this.timeout = Integer.parseInt(args[2]);
        this.fileFolder = args[3];

        fileIndex = new ConcurrentHashMap<String, Integer>();

        try {
            DstoreLogger.init(Logger.LoggingType.ON_FILE_AND_TERMINAL, port);
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        mainSequence();
    }

    private void mainSequence()
    {
        try
        {
            Socket controller = new Socket(InetAddress.getByName("localhost"), cport);

            BufferedReader controllerInput = new BufferedReader(new InputStreamReader(controller.getInputStream()));
            PrintWriter controllerOutput = new PrintWriter(new OutputStreamWriter(controller.getOutputStream()), true);

            //This is the thread that communicates with the controller
            new Thread(() -> {
                try 
                {
                    Path folderPath = Paths.get(System.getProperty("user.dir") + File.separator + fileFolder + File.separator);
                    if(Files.exists(folderPath))
                    {
                        File dir = new File(folderPath.toString());
                        File[] fileList = dir.listFiles();
                        for(File file: fileList)
                        {
                            file.delete();
                        }
                    }
                    else
                    {
                        Files.createDirectory(folderPath);
                    }
                    log("Cleared FileFolder");
                    
                    //Deletes previous entry and creates a new storage directory
                    try
                    {   
                        String msg = Protocol.JOIN_TOKEN + " " + port;
                        controllerOutput.println(msg);
                        DstoreLogger.getInstance().messageSent(controller, msg);
                        log("Sent JOIN request to Controller.");
                    }
                    catch(Exception e)
                    {
                        log("Could not write to controller "+e);
                        return;
                    }

                    for(;;)
                    {
                        try
                        {
                            if(controllerInput.ready())
                            {
                                String input = controllerInput.readLine();
                                String[] message = input.split(" ");

                                if(input.equals(null))
                                {
                                    controllerInput.close();
                                    break;
                                }

                                DstoreLogger.getInstance().messageReceived(controller, input);

                                String command = message[0];

                                //Controller List Command
                                if(command.equals(Protocol.LIST_TOKEN))
                                {   
                                    try
                                    {
                                        String test = message[1];
                                        log("Command " + Protocol.LIST_TOKEN + " contained more arguments than expected. Continuing.");
                                        continue;
                                    }
                                    catch(Exception ignored){}

                                    log("Controller " + controller.getPort() + " sent command LIST .");

                                    String fileList = "";
                                    Set<String> keys;
                                    synchronized(fileLock)
                                    {
                                        keys = fileIndex.keySet();
                                    }

                                    for(String key: keys)
                                    {
                                        fileList += key + " ";
                                    }
                                    fileList = fileList.substring(0, fileList.length()-1);

                                    String msg = Protocol.LIST_TOKEN + " " + fileList;
                                    controllerOutput.println(msg);
                                    DstoreLogger.getInstance().messageSent(controller, msg);
                                }
                                //Controller REMOVE command
                                else if(command.equals(Protocol.REMOVE_TOKEN))
                                {   
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
                                        if(fileIndex.containsKey(fileName))
                                        {
                                            isContained = true;
                                        }
                                    }

                                    if(!isContained)
                                    {
                                        log("Controller " + controller.getPort() + " requested to remove file " + fileName + " " + " but it doesn't exist.");
                                        String msg = Protocol.ERROR_FILE_DOES_NOT_EXIST_TOKEN + " " + fileName;
                                        controllerOutput.println(msg);
                                        DstoreLogger.getInstance().messageSent(controller, msg);
                                        continue;
                                    }

                                    log("Controller " + controller.getPort() + " sent command REMOVE for file " + fileName);

                                    synchronized(fileLock)
                                    {
                                        fileIndex.remove(fileName);
                                    }

                                    File file = new File("");
                                    String currentPath = file.getAbsolutePath();
                                    file = new File(currentPath + File.separator + fileFolder + File.separator + fileName);
                                    file.delete();

                                    log("File " + fileName + " successfully removed.");

                                    String msg = Protocol.REMOVE_ACK_TOKEN + " " + fileName;
                                    controllerOutput.println(msg);
                                    DstoreLogger.getInstance().messageSent(controller, msg);
                                }
                            }
                        }
                        catch(Exception e)
                        {
                            log("Could not read from controller"+e);
                        }
                    }

                }
                catch(Exception ignored){}
            }).start();

            //Looks for clients to connect to
            try
            {
                ServerSocket ss = new ServerSocket(port);
                
                for(;;)
                {
                    try
                    {
                        log("Awaiting connecting with client.");
                        Socket client = ss.accept();
                        log("Connection with client: " + client.getPort() + " established.");
                        //Probably not required
                        // if(client.getPort() == cport)
                        // {
                        //     log("Controller attempted to connect as a client!!!");
                        //     continue;
                        // }

                        //This is the thread that communicates with the client
                        new Thread(() -> {
                            try
                            {
                                BufferedReader clientInput = new BufferedReader(new InputStreamReader(client.getInputStream()));
                                PrintWriter clientOutput = new PrintWriter(new OutputStreamWriter(client.getOutputStream()),true);
                                
                                InputStream clientInputStream = client.getInputStream();
                                OutputStream clientOutputStream = client.getOutputStream();           
        
                                for(;;)
                                {
                                    try
                                    {
                                        if(clientInput.ready())
                                        {
                                            String input = clientInput.readLine();
                                            String[] message = input.split(" ");

                                            if(input.equals(null))
                                            {
                                                clientInput.close();
                                                break;
                                            }

                                            DstoreLogger.getInstance().messageReceived(client, input);

                                            String command = message[0];
        
                                            if(command.equals(Protocol.STORE_TOKEN))
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

                                                log("Client " + client.getPort() + " sent command STORE . FileName: " + fileName + " FileSize: " + fileSize);
                                                
                                                String msg = Protocol.ACK_TOKEN;
                                                clientOutput.println(msg);
                                                DstoreLogger.getInstance().messageSent(client, msg);

                                                log("Sent ACK to client " + client.getPort() + " for file: " + fileName);

                                                File file = new File("");
                                                String currentPath = file.getAbsolutePath();
                                                file = new File(currentPath + File.separator + fileFolder + File.separator + fileName);
        
                                                log("Starting to receive file of size: " + Integer.toString(fileSize));

                                                byte[] contentBuf = clientInputStream.readNBytes(fileSize);
                                                log("Received file " + fileName + " from client " + client.getPort());
        
                                                FileOutputStream fo = new FileOutputStream(file);
                                                fo.write(contentBuf);
                                                fo.close();
                                                
                                                log("Sending STORE_ACK token to controller " + controller.getPort());
                                                msg = Protocol.STORE_ACK_TOKEN + " " + fileName;
                                                controllerOutput.println(msg);
                                                DstoreLogger.getInstance().messageSent(controller, msg);

                                                synchronized(fileLock)
                                                {
                                                    fileIndex.put(fileName, fileSize);
                                                }

                                                client.close();
                                                log("Client " + client.getPort() + " request complete. Closing connection.");
                                                break;
                                            }
                                            else if(command.equals(Protocol.LOAD_DATA_TOKEN))
                                            {
                                                String fileName;

                                                try
                                                {
                                                    fileName = message[1];
                                                }
                                                catch(Exception e)
                                                {
                                                    log("Malformed content of message of command: " + Protocol.LOAD_DATA_TOKEN);
                                                    continue;
                                                }

                                                try
                                                {
                                                    String test = message[2];
                                                    log("Command " + Protocol.LOAD_DATA_TOKEN + " contained more arguments than expected. Continuing.");
                                                    continue;
                                                }
                                                catch(Exception ignored){}

                                                log("Client " + client.getPort() + " sent command LOAD_DATA . FileName: " + fileName);
                                                
                                                boolean isContained = false;

                                                synchronized(fileLock)
                                                {
                                                    if(fileIndex.containsKey(fileName))
                                                    {
                                                        isContained = true;
                                                    }
                                                }

                                                //Might require synchronization
                                                if(isContained)
                                                {
                                                    File file = new File("");
                                                    String currentPath = file.getAbsolutePath();
                                                    file = new File(currentPath + File.separator + fileFolder + File.separator + fileName);

                                                    byte[] bytes = Files.readAllBytes(file.toPath());

                                                    log("Sending to client " + client.getPort() + " the contents of " + fileName);                                                    clientOutputStream.write(bytes);
                                                    clientOutputStream.flush();
                                                    client.close();
                                                }
                                                else
                                                {
                                                    //Simply close connection with client if file is not present.
                                                    client.close();
                                                }
                                            }
                                        }
                                    }
                                    catch(Exception e)
                                    {
                                        log("error "+e);
                                    }
                                }
                            }
                            catch(Exception e)
                            {
                                log("error " +e);
                            }
                        }).start();
                    }
                    catch(Exception e)
                    {
                        log("error "+e);
                    }
                }
            }
            catch(Exception e)
            {
                log("error "+e);
            }
        }
        catch(Exception e)
        {
            log("error "+e);
        }
    }

    private void log(String message)
    {
        Timestamp timestamp = new Timestamp(System.currentTimeMillis());
        System.out.println(timestamp + " " + message);
    }

    public static void main(String[] args)
    {
        Dstore storage = new Dstore(args);
    }
}