package mca.network.s2c;

import mca.ClientProxy;
import mca.cobalt.network.Message;

import java.io.Serial;

public class BabyNameResponse implements Message {
    @Serial
    private static final long serialVersionUID = -2800883604573859252L;

    private final String name;

    public BabyNameResponse(String name) {
        this.name = name;
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleBabyNameResponse(this);
    }

    public String getName() {
        return name;
    }
}
