package org.example.cpm;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.eclipse.mosaic.lib.objects.v2x.EncodedPayload;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;

public class CoopPercepMessage extends V2xMessage {

    private final CoopPercepData content;

    public CoopPercepMessage(MessageRouting routing, CoopPercepData content) {
        super(routing);
        this.content = content;
    }

    @NonNull
    @Override
    public EncodedPayload getPayload() {
        return new EncodedPayload(content, 0);
    }

    public CoopPercepData getContent() {
        return content;
    }
}
