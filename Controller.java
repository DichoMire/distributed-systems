import java.io.BufferedReader;
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

    private ConcurrentHashMap<Integer, Socket> storages = new ConcurrentHashMap<Integer, Socket>();

    private ConcurrentHashMap<String, FileInfo> fileIndex = new ConcurrentHashMap<String, FileInfo>();

    //Concurrent queue for operations added in queue during rebalance

    //int cport, int replication, int timeout, int rebalancePeriod
    public Controller (String [] args)
    {
        this.cport = Integer.parseInt(args[0]);
        this.replication = Integer.parseInt(args[1]);
        this.timeout = Integer.parseInt(args[2]);
        this.rebalancePeriod = Integer.parseInt(args[3]);
        this.state = States.INITIAL_CONTROLLER;
        System.out.println("Controller initialized");

        connectedStorages = 0;
        // this.cport = cport;
        // this.replication = replication;
        // this.timeout = timeout;
        // this.rebalancePeriod = rebalancePeriod;
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
                    System.out.println("Waiting connection");
                    Socket contact = ss.accept();
                    System.out.println("Connected to port: " + contact.getLocalPort());

                    new Thread(() -> {
                        try
                        {
                            BufferedReader contactInput = new BufferedReader(new InputStreamReader(contact.getInputStream()));
                            PrintWriter contactOutput = new PrintWriter(new OutputStreamWriter(contact.getOutputStream()),true);

                            for(;;)
                            { 
                                if(contactInput.ready())
                                {
                                    String firstBuffer = contactInput.readLine();
                                    int firstSpace = firstBuffer.indexOf(" ");
                                    String command;
                                    try
                                    {
                                        command = firstBuffer.substring(0, firstSpace);
                                    }
                                    catch(Exception e)
                                    {
                                        command = "Missing command";
                                    }

                                    System.out.println("Command is: " + command);

                                    //Join command
                                    //This command is only used by DStores to initialize
                                    if(command.equals(Protocol.JOIN_TOKEN)){
                                        int secondSpace = firstBuffer.indexOf(" ", firstSpace + 1);
                                        int port = Integer.parseInt(firstBuffer.substring(firstSpace + 1, secondSpace));

                                        //Is this even required?
                                        if(!storages.contains(port))
                                        {
                                            storages.put(port, contact);
                                            connectedStorages++;
                                        }
                                    }
                                    //Client store command
                                    else if(command.equals(Protocol.STORE_TOKEN)){
                                        int secondSpace = firstBuffer.indexOf(" ", firstSpace + 1);
                                        int thirdSpace = firstBuffer.indexOf(" ", secondSpace + 1);

                                        String fileName = firstBuffer.substring(firstSpace + 1, secondSpace);
                                        int fileSize = Integer.parseInt(firstBuffer.substring(secondSpace + 1, thirdSpace));
                                        
                                        //We need to check if file is present

                                        contactOutput.println(Protocol.STORE_TO_TOKEN + " " + getStoreToPorts());
                                    }
                                    else if(command.equals(Protocol.LIST_TOKEN))
                                    {
                                        Set<String> keys = fileIndex.keySet();

                                        String fileList = "";

                                        for(String key: keys)
                                        {
                                            fileList += key + " ";
                                        }
                                        fileList = fileList.substring(0, fileList.length()-1);

                                        contactOutput.println(Protocol.LIST_TOKEN + " " + fileList);
                                    }
                                    else if(command.equals(Protocol.STORE_ACK_TOKEN))
                                    {

                                    }
                                }
                            }
                        } 
                        catch(Exception e)
                        {
                            //No connection found
                            System.out.println("error "+e);
                        }
                    }).start();
                    contact.close(); 
                }   
                catch(Exception e)
                {
                    //No connection found
                    System.out.println("error "+e);
                }
            }
        }
        catch(Exception e)
        {
            System.out.println("error "+e);
            //Error with Server Socket setup
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