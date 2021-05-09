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
                            System.out.println("error "+e);
                        }
                    }

                }
                catch(Exception e)
                {
                    
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
                        Socket client = ss.accept();
                        //Probably not required
                        if(client.getPort() == cport)
                        {
                            continue;
                        }

                        //This is the thread that communicates with the client
                        new Thread(() -> {
                            try
                            {
                                BufferedReader clientInput = new BufferedReader(new InputStreamReader(controller.getInputStream()));
                                PrintWriter clientOutput = new PrintWriter(new OutputStreamWriter(controller.getOutputStream()));
                                
                                InputStream clientInputStream = controller.getInputStream();
                                OutputStream clientOutputStream = controller.getOutputStream();           
        
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

                                                File file = new File("");
                                                String currentPath = file.getAbsolutePath();
                                                file = new File(currentPath + File.separator + fileName);
        
                                                // try
                                                // {
                                                //     file.createNewFile();
                                                // }
                                                // catch(Exception e)
                                                // {
                                                //     System.out.println("error "+e);
                                                // }
        
                                                byte[] contentBuf = clientInputStream.readNBytes(fileSize);
                                                String content = new String(contentBuf);
        
                                                FileWriter fw = new FileWriter(file);
                                                fw.write(content);
                                                fw.close();
                                                
                                                controllerOutput.println(Protocol.STORE_ACK_TOKEN + " " + fileName);

                                                fileIndex.put(fileName, fileSize);

                                                client.close();
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