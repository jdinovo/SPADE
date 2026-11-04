package org.example.intersection;

import edu.umd.cs.findbugs.annotations.NonNull;
import org.eclipse.mosaic.lib.objects.v2x.EncodedPayload;
import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;

public class MapDataMessage extends V2xMessage {

    private final MapData content;

    public MapDataMessage(MessageRouting routing, MapData content) {
        super(routing);
        this.content = content;
    }

    @NonNull
    @Override
    public EncodedPayload getPayload() {
        return new EncodedPayload(content, 0);
    }

    public MapData getContent() {
        return content;
    }
}
