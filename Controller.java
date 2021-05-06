import java.net.ServerSocket;

public class Controller {

    private int cport;
    private int replication;
    private int timeout;
    private int rebalancePeriod;

    public Controller (int cport, int replication, int timeout, int rebalancePeriod)
    {
        this.cport = cport;
        this.replication = replication;
        this.timeout = timeout;
        this.rebalancePeriod = rebalancePeriod;
    }
}