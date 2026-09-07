package com.createdtr.defendtherealm.verification;

import com.enxv.aeronauticsstructuretool.blueprint.codec.BlueprintArchiveCodec;
import com.enxv.aeronauticsstructuretool.blueprint.codec.NativeBlueprintReader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Uses the Toolgun's compiled codec API; never places or rewrites the blueprint. */
public final class InspectBlueprint {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("blueprint path required");
        byte[] bytes = Files.readAllBytes(Path.of(args[0]));
        var root = BlueprintArchiveCodec.decodeCompressedOrRaw(bytes);
        var document = NativeBlueprintReader.read(root);
        System.out.println("format=" + document.format() + " root=" + document.rootBlueprintId()
                + " sublevels=" + document.sublevels().size() + " bytes=" + bytes.length);
        for (var saved : document.sublevels()) {
            System.out.println("sublevel blueprint=" + saved.blueprintId() + " original=" + saved.originalId()
                    + " name=" + saved.name() + " runtimeContraptions=" + saved.runtimeContraptions().size()
                    + " superGlue=" + saved.superGlueEntries().size() + " honeyGlue=" + saved.honeyGlueEntries().size()
                    + " localAnchor=" + saved.localAnchor()
                    + " plotKeys=" + saved.plotTag().getAllKeys());
            saved.runtimeContraptions().forEach(runtime -> System.out.println("  runtime kind=" + runtime.kind()
                    + " controller=" + runtime.controllerLocalPos() + " materials=" + runtime.materialItemCounts()
                    + " bottomless=" + runtime.contraptionTag().getBoolean("BottomlessSupply")
                    + " contraptionKeys=" + runtime.contraptionTag().getAllKeys()));
        }
    }
}
