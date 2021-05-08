import java.util.HashMap;

public class Storage {
    private String state;

    private HashMap<String, Integer> files = new HashMap<String, Integer>(); 

    public Storage()
    {
        state = "";
        files = new HashMap<String, Integer>();
    }

    public void setState(String state)
    {
        this.state = state;
    }

    public String getState()
    {
        return state;
    }

    //Functions for file list functionality

    //Add a file
    
    //Remove a file

    //Return a space separated list of files
}
