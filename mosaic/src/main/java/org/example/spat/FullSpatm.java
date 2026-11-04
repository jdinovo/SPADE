package org.example.spat;

import org.eclipse.mosaic.lib.objects.v2x.MessageRouting;
import org.eclipse.mosaic.lib.objects.v2x.V2xMessage;
import org.eclipse.mosaic.lib.objects.v2x.EncodedPayload;

import javax.annotation.Nonnull;

public class FullSpatm extends V2xMessage {
    private final FullSpatContent content;

    public FullSpatm(MessageRouting routing, FullSpatContent content) {
        super(routing);
        this.content = content;
    }

    @Override
    @Nonnull
    public EncodedPayload getPayload() {
        return new EncodedPayload(content, 0);
    }

    public FullSpatContent getContent() {
        return content;
    }
}
