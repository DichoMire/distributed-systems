import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.Socket;

public class StorageInfo {

    private Socket socket;

    private BufferedReader storageInput;
    private PrintWriter storageOutput;
    

    public StorageInfo(Socket socket, BufferedReader storageInput, PrintWriter storageOutput)
    {
        this.socket = socket;
        this.storageInput = storageInput;
        this.storageOutput = storageOutput;
    }

    public Socket getSocket()
    {
        return socket;
    }

    public BufferedReader getInput()
    {
        return storageInput;
    }

    public PrintWriter getOutput()
    {
        return storageOutput;
    }
}
