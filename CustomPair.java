public class CustomPair {
    private boolean left;
    private Integer right;

    public CustomPair(boolean left, Integer right)
    {
        this.left = left;
        this.right = right;
    }

    public boolean getBoolean()
    {
        return left;
    }

    public Integer getInteger()
    {
        return right;
    }

    public void getBoolean(boolean left)
    {
        this.left = left;
    }

    public void getInteger(Integer right)
    {
        this.right = right;
    }
}
