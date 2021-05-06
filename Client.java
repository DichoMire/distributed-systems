import java.net.ServerSocket;

public class Client {

    private int cport;
    private int timeout;

    public Client(int cport, int timeout)
    {
        this.cport = cport;
        this.timeout = timeout;
    }
}