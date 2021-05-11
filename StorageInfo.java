import java.io.BufferedReader;
import java.io.PrintWriter;
import java.net.Socket;

public class StorageInfo {

    private Socket socket;

    private BufferedReader storageInput;
    private PrintWriter storageOutput;

    private int numberOfFiles;

    public StorageInfo(Socket socket, BufferedReader storageInput, PrintWriter storageOutput)
    {
        this.socket = socket;
        this.storageInput = storageInput;
        this.storageOutput = storageOutput;
        numberOfFiles = 0;
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

    public void increaseFiles()
    {
        numberOfFiles++;
    }

    public void decreaseFiles()
    {
        numberOfFiles--;
    }

    public int getNumberOfFiles()
    {
        return numberOfFiles;
    }

    public int getPort()
    {
        return socket.getPort();
    }
}
