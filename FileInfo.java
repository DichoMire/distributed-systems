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

    public FileInfo()
    {
        state = States.INITIAL_FILE;
        storages = new ArrayList<Integer>();
    }

    public void setState(int state)
    {
        this.state = state;
    }

    public int getState()
    {
        return state;
    }

    //Functions for file list functionality

    //Add a file
    
    //Remove a file

    //Return a space separated list of files
}
