import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.BufferedWriter;
import java.io.InputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;

import jdk.tools.jaotc.ELFMacroAssembler;

public class Controller {

    private int cport;
    private int replication;
    private int timeout;
    private int rebalancePeriod;

    private string state;

    //int cport, int replication, int timeout, int rebalancePeriod
    public Controller (String [] args)
    {
        this.cport = Integer.parseInt(args[0]);
        this.replication = Integer.parseInt(args[1]);
        this.timeout = Integer.parseInt(args[2]);
        this.rebalancePeriod = Integer.parseInt(args[3]);
        this.state = "Waiting";
        // this.cport = cport;
        // this.replication = replication;
        // this.timeout = timeout;
        // this.rebalancePeriod = rebalancePeriod;
    }

    // private void parseInput()
    // {
    //     this.cport = Integer.parseInt(args[0]);
    //     this.replication = Integer.parseInt(args[1]);
    //     this.timeout = Integer.parseInt(args[2]);
    //     this.rebalancePeriod = Integer.parseInt(args[3]);
    // }

    public static void main(String [] args)
    {
        Controller mainCoontroller = new Controller(args);

        mainCoontroller.mainSequence();
    }

    public void mainSequence()
    {
        try
        {
            ServerSocket ss = new ServerSocket(cport);
            for(;;)
            {
                try
                {
                    //Awaits connection
                    Socket client = ss.accept();
                    new Thread(() -> {
                        //Mitak Copy
                        BufferedReader clientInput = new BufferedReader(new InputStreamReader(client.getInputStream()));
                        //Mitak Copy
                        BufferedWriter clientOutput = new BufferedWriter(new InputStreamWriter(client.getOutputStream()));
                        
                        InputStreamReader in = client.getInputStream();

                        byte[] buf = new byte[1000];
                        int buflen;

                        for(;;)
                        {
                            buflen = in.read(buf);
                            if(buflen != -1)
                            {
                                String firstBuffer = new String(buf, 0, buflen);
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

                                //Store command
                                if(command.equals(Protocol.STORE_TOKEN)){
                                    int secondSpace = firstBuffer.indexOf(" ", firstSpace + 1);
                                    int thirdSpace = firstBuffer.indexOf(" ", secondSpace + 1);

                                    String fileName = firstBuffer.substring(firstSpace + 1, secondSpace);
                                    int fileSize = Integer.parseInt(firstBuffer.substring(secondSpace + 1, thirdSpace));

                                    clientOutput.write(Protocol.STORE_TO_TOKEN + " " + getStoreToPorts());
                                    clientOutput.flush();
                                }
                                else if(command.equals(Protocol.STORE_ACK_TOKEN))
                                {

                                }
                                else if(true)
                                {

                                }
                            }
                            else
                            {
                                //Do nothing
                            }
                        }

                        //???????
                        client.close(); 
                    });
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
        return "";
    }
}