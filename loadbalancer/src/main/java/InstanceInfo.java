import com.amazonaws.services.ec2.model.Instance;

public class InstanceInfo {

    private Instance instance;
    private int numCurrentRequests = 0;
    private boolean willTerminate = false;
    private double lastCpuMeasured = 0;
    private boolean isFresh = true;
    private double work = 0;

    public InstanceInfo(Instance instance) {
        this.instance = instance;
    }

    public Instance getInstance() {
        return instance;
    }

    public synchronized void setInstance(Instance instance) {
        this.instance = instance;
    }

    public int getNumCurrentRequests() {
        return numCurrentRequests;
    }

    public synchronized void incrementNumCurrentRequests() {
        this.numCurrentRequests++;
    }

    public synchronized void decrementNumCurrentRequests() {
        this.numCurrentRequests--;
    }

    public boolean willTerminate() {
        return willTerminate;
    }

    public void setTerminate(boolean willTerminate) {
        this.willTerminate = willTerminate;
    }

    public double getLastCpuMeasured() {
        return lastCpuMeasured;
    }

    public void setLastCpuMeasured(double lastCpuMeasured) {
        this.lastCpuMeasured = lastCpuMeasured;
    }

    public boolean isFresh() {
        return isFresh;
    }

    public void setFresh(boolean fresh) {
        isFresh = fresh;
    }

    public double getWork() {
        return work;
    }

    public synchronized void incrementWork(double incr) {
        this.work += incr;
    }

    public synchronized void decrementWork(double decr) {
        this.work -= decr;
    }

}
