package mca.network.s2c;

import mca.ClientProxy;
import mca.client.gui.Constraint;
import mca.cobalt.network.Message;
import mca.entity.ai.relationship.MarriageState;

import java.io.Serial;
import java.util.Set;

public class GetInteractDataResponse implements Message {
    @Serial
    private static final long serialVersionUID = -4168503424192658779L;

    public final Set<Constraint> constraints;
    public final String father;
    public final String mother;
    public final String spouse;
    public final MarriageState marriageState;

    public GetInteractDataResponse(Set<Constraint> constraints, String father, String mother, String spouse, MarriageState marriageState) {
        this.constraints = constraints;
        this.father = father;
        this.mother = mother;
        this.spouse = spouse;
        this.marriageState = marriageState;
    }

    @Override
    public void receive() {
        ClientProxy.getNetworkHandler().handleInteractDataResponse(this);
    }
}
