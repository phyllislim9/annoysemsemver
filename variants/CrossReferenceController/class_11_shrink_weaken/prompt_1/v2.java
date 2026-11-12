package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.*;
import org.cbioportal.genome_nexus.model.GeneXref;
import org.cbioportal.genome_nexus.service.GeneXrefService;
import org.cbioportal.genome_nexus.service.exception.EnsemblWebServiceException;
import org.cbioportal.genome_nexus.web.config.InternalApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@InternalApi
@RestController // shorthand for @Controller, @ResponseBody
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
@Api(tags = "cross-reference-controller", description = "Cross Reference Controller")
public class CrossReferenceControllerV2
{
private final GeneXrefService geneXrefService;

    @Autowired
public CrossReferenceControllerV2(GeneXrefService geneXrefService)
    {
this.geneXrefService = geneXrefService;
    }

    @RequestMapping(value = "/xrefs/{accession}/count", method = RequestMethod.GET, produces = "application/json")
    public int countGeneXrefs(@PathVariable String accession) throws EnsemblWebServiceException
    {
        return geneXrefService.getGeneXrefs(accession).size();
    }

}
