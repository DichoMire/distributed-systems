public class States {
    //States of Dstore
    public final static int INITIAL_DSTORE = 0;
    public final static int AWAITING_FILE = 1;
    public final static int STORED_FILE = 2;

    //States of Controller
    public final static int INITIAL_CONTROLLER = 200;

    //States of files
    public final static int INITIAL_FILE = 100;
    public final static int STORE_IN_PROGRESS = 101;
    public final static int STORE_COMPLETE = 102;
    public final static int REMOVE_IN_PROGRESS = 103;
    public final static int REMOVE_COMPLETE = 104;
}
