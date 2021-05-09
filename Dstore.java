import java.io.BufferedReader;
import java.io.File;
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
import java.util.HashMap;
import java.util.Set;

public class Dstore {
    
    private int port;
    private int cport;
    private int timeout;
    private String fileFolder;

    // public Dstore (int port, int cport, int timeout, String fileFolder)
    // {
    //     this.port = port;
    //     this.cport = cport;
    //     this.timeout = timeout;
    //     this.fileFolder = fileFolder;
    // }

    //Hashmap with clients?

    private HashMap<String, Integer> fileIndex;

    public Dstore (String[] args)
    {
        this.port = Integer.parseInt(args[0]);
        this.cport = Integer.parseInt(args[1]);
        this.timeout = Integer.parseInt(args[2]);
        this.fileFolder = args[3];

        fileIndex = new HashMap<String, Integer>();



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
                    controllerOutput.println(Protocol.JOIN_TOKEN + " " + port);
                    //Deletes previous entry and creates a new storage directory
                    try
                    {
                        Path currentPath = Paths.get(System.getProperty("user.dir") + File.separator + fileFolder + File.separator);
                        if(Files.exists(currentPath))
                        {
                            File dir = new File(currentPath.toString());
                            File[] fileList = dir.listFiles();
                            for(File file: fileList)
                            {
                                file.delete();
                            }
                        }
                        else
                        {
                            Files.createDirectory(currentPath);
                        }
                    }
                    catch(Exception ignored){};
                    
                    System.out.println("Sent request to controller to join.");
                    
                    for(;;)
                    {
                        try
                        {
                            if(controllerInput.ready())
                            {
                                String[] message = controllerInput.readLine().split(" ");
                                String command = message[0];

                                System.out.println("Command is: " + command);

                                if(command.equals(Protocol.LIST_TOKEN))
                                {   
                                    String fileList = "";
                                    Set<String> keys = fileIndex.keySet();
                                    for(String key: keys)
                                    {
                                        fileList += key + " ";
                                    }
                                    fileList = fileList.substring(0, fileList.length()-1);

                                    controllerOutput.println(Protocol.LIST_TOKEN + " " + fileList);
                                }
                            }
                        }
                        catch(Exception e)
                        {
                            System.out.println("Could not read from controller"+e);
                        }
                    }

                }
                catch(Exception e)
                {
                    System.out.println("Could not write to controller "+e);
                }
            }).start();

            //Looks for clients to connect to
            try
            {
                ServerSocket ss = new ServerSocket(port);
                
                for(;;)
                {
                    try
                    {
                        System.out.println("Awaiting connecting with client.");
                        Socket client = ss.accept();
                        System.out.println("Connection with client established.");
                        //Probably not required

                        System.out.println("Cport: " + cport);
                        System.out.println("Client port: " + client.getPort());
                        if(client.getPort() == cport)
                        {
                            System.out.println("Controller attempted to connect as a client!!!");
                            continue;
                        }

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
                                        // buflen = in.read(buf);
                                        if(clientInput.ready())
                                        {
                                            String[] message = clientInput.readLine().split(" ");
                                            String command = message[0];

                                            System.out.println("Command is: " + command);
        
                                            if(command.equals(Protocol.STORE_TOKEN))
                                            {
                                                String fileName = message[1];
                                                int fileSize = Integer.parseInt(message[2]);
                                                
                                                clientOutput.println(Protocol.ACK_TOKEN);

                                                System.out.println("Sent ACK to client for file: " + fileName);

                                                File file = new File("");
                                                String currentPath = file.getAbsolutePath();
                                                file = new File(currentPath + File.separator + fileFolder + File.separator + fileName);
        
                                                // try
                                                // {
                                                //     file.createNewFile();
                                                // }
                                                // catch(Exception e)
                                                // {
                                                //     System.out.println("error "+e);
                                                // }
                                                System.out.println("Starting to receive file of size: " + Integer.toString(fileSize));

                                                byte[] contentBuf = clientInputStream.readNBytes(fileSize);
                                                System.out.println("Received file " + fileName + " from client");

                                                String content = new String(contentBuf);
        
                                                FileWriter fw = new FileWriter(file);
                                                fw.write(content);
                                                fw.close();
                                                
                                                controllerOutput.println(Protocol.STORE_ACK_TOKEN + " " + fileName);

                                                fileIndex.put(fileName, fileSize);

                                                client.close();
                                                System.out.println("Client request complete. Closing connection.");
                                                break;
                                            }
                                        }
                                    }
                                    catch(Exception e)
                                    {
                                        System.out.println("error "+e);
                                    }
                                }
                            }
                            catch(Exception e)
                            {
                                System.out.println("error " +e);
                            }
                        }).start();
                    }
                    catch(Exception e)
                    {
                        System.out.println("error "+e);
                    }
                }
            }
            catch(Exception e)
            {
                System.out.println("error "+e);
            }
        }
        catch(Exception e)
        {
            System.out.println("error "+e);
        }
    }

    public static void main(String[] args)
    {
        Dstore storage = new Dstore(args);
    }
}