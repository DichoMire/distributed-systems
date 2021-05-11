import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
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
                        controllerOutput.println(Protocol.JOIN_TOKEN + " " + port);
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
                                String[] message = controllerInput.readLine().split(" ");
                                String command = message[0];

                                //Controller List Command
                                if(command.equals(Protocol.LIST_TOKEN))
                                {   
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

                                    controllerOutput.println(Protocol.LIST_TOKEN + " " + fileList);
                                }
                                //Controller REMOVE command
                                else if(command.equals(Protocol.REMOVE_TOKEN))
                                {   
                                    String fileName = message[1];
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

                                    controllerOutput.println(Protocol.REMOVE_ACK_TOKEN + " " + fileName);
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
                                            String[] message = clientInput.readLine().split(" ");
                                            String command = message[0];
        
                                            if(command.equals(Protocol.STORE_TOKEN))
                                            {
                                                String fileName = message[1];
                                                int fileSize = Integer.parseInt(message[2]);

                                                log("Client " + client.getPort() + " sent command STORE . FileName: " + fileName + " FileSize: " + fileSize);
                                                
                                                clientOutput.println(Protocol.ACK_TOKEN);

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
                                                controllerOutput.println(Protocol.STORE_ACK_TOKEN + " " + fileName);

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
                                                String fileName = message[1];

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