import java.net.ServerSocket;

public class Dstore {
    
    private int port;
    private int cport;
    private int timeout;
    private String fileFolder;

    public Dstore (int port, int cport, int timeout, String fileFolder)
    {
        this.port = port;
        this.cport = cport;
        this.timeout = timeout;
        this.fileFolder = fileFolder;
    }
}