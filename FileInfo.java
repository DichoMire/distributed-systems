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

    private ArrayList<Integer> availableStorages;

    private int fileSize;

    private int replicationFactor;

    private int remainingAcks;

    private Socket lastModifier;

    public FileInfo(int fileSize, int replicationFactor, String ports, Socket lastModifier)
    {
        this.fileSize = fileSize;
        state = States.STORE_IN_PROGRESS;
        storages = new ArrayList<Integer>();
        setStorages(ports);
        this.lastModifier = lastModifier;
        this.replicationFactor = replicationFactor;
        remainingAcks = replicationFactor;
    }

    //Decreases ACKs required by one.
    //Returns true if store has been completed
    //False otherwise
    public boolean decreaseAcks()
    {
        remainingAcks--;
        if(remainingAcks == 0)
        {
            if(state == States.STORE_IN_PROGRESS)
            {
                state = States.STORE_COMPLETE;
            }
            else if(state == States.REMOVE_IN_PROGRESS)
            {
                state = States.REMOVE_COMPLETE;
            }
            return true;
        }
        return false;
    }

    //Creates a new Array list that copies storages
    //This stores available storages from which to attempt LOADS or RELOADS
    public void initializeAvailableStorages()
    {
        availableStorages = new ArrayList<Integer>(storages);
    }

    public void setState(int state)
    {
        this.state = state;
    }

    public void setStateRemove(Socket lastModifier)
    {
        state = States.REMOVE_IN_PROGRESS;
        remainingAcks = replicationFactor;
        this.lastModifier = lastModifier;
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

    public ArrayList<Integer> getStorages()
    {
        return new ArrayList<Integer>(storages);
    }

    public Integer getSingleAvailable()
    {
        return availableStorages.get(0);
    }

    public void removeFirstAvailable()
    {
        availableStorages.remove(0);
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
