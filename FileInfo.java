import java.net.Socket;
import java.util.ArrayList;

public class FileInfo {
    //Values such as
    //Store in progress
    //Store complete
    //Remove in progress
    //Remove complete
    private int state;

    private ArrayList<Integer> storages;

    private int fileSize;

    private int remainingAcks;

    private Socket lastModifier;

    public FileInfo(int fileSize, int remainingAcks, String ports, Socket lastModifier)
    {
        this.fileSize = fileSize;
        state = States.STORE_IN_PROGRESS;
        storages = new ArrayList<Integer>();
        setStorages(ports);
        this.lastModifier = lastModifier;
        this.remainingAcks = remainingAcks;
    }

    //Decreases ACKs required by one.
    //Returns true if store has been completed
    //False otherwise
    public boolean decreaseAcks()
    {
        remainingAcks--;
        if(remainingAcks == 0)
        {
            state = States.STORE_COMPLETE;
            return true;
        }
        return true;
    }

    public void setState(int state)
    {
        this.state = state;
    }

    public int getState()
    {
        return state;
    }

    public void setSize(int fileSize)
    {
        this.fileSize = fileSize;
    }

    public int getSize()
    {   
        return fileSize;
    }

    public void setStorages(String input)
    {
        String[] list = input.split(" ");
        for(String stor: list)
        {
            storages.add(Integer.parseInt(stor));
        }
    }

    public String getStorages()
    {
        String result = "";
        for(Integer stor: storages)
        {
            result += Integer.toString(stor) + " ";
        }
        if(result.length() > 0)
        {
            result.substring(0, result.length() - 1);
        }
        return result;
    }

    public void removeStorage(Integer port)
    {
        storages.remove(port);
    }

    public Socket getModifier()
    {
        return lastModifier;
    }

    //No remove file functionality.

    //No rebalance functionality
}
