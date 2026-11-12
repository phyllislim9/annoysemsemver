package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.cbioportal.genome_nexus.model.VariantAnnotationSummary;
import org.cbioportal.genome_nexus.service.VariantAnnotationSummaryService;
import org.cbioportal.genome_nexus.service.exception.VariantAnnotationNotFoundException;
import org.cbioportal.genome_nexus.service.exception.VariantAnnotationWebServiceException;
import org.cbioportal.genome_nexus.web.config.InternalApi;
import org.cbioportal.genome_nexus.web.param.TranscriptSummaryProjection;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@InternalApi
@RestController // shorthand for @Controller, @ResponseBody
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
public class AnnotationSummaryControllerV2
{
    private final VariantAnnotationSummaryService variantAnnotationSummaryService;

    @Autowired
    public AnnotationSummaryControllerV2(VariantAnnotationSummaryService variantAnnotationSummaryService)
    {
        this.variantAnnotationSummaryService = variantAnnotationSummaryService;
    }

}
