package it.piero.notiva.utils;

import it.piero.notiva.model.BBox;
import it.piero.notiva.model.Region;
import org.springframework.stereotype.Component;

@Component
public class RegionUtils {

    public static Region from(BBox bbox) {
        if (bbox == null || bbox.getTop() == null) return Region.BODY;
        float top = bbox.getTop();
        if (top < 0.15f) return Region.HEADER;
        if (top > 0.85f) return Region.FOOTER;
        return Region.BODY;
    }
}
