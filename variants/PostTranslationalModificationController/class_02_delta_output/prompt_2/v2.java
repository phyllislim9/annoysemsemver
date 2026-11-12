package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.cbioportal.genome_nexus.model.PostTranslationalModification;
import org.cbioportal.genome_nexus.service.PostTranslationalModificationService;
import org.cbioportal.genome_nexus.web.config.PublicApi;
import org.cbioportal.genome_nexus.web.param.PtmFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@PublicApi
@RestController // shorthand for @Controller, @ResponseBody
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
@Api(tags = "ptm-controller", description = "PTM Controller")
public class PostTranslationalModificationControllerV2
{
private final PostTranslationalModificationService postTranslationalModificationService;

    @Autowired
public PostTranslationalModificationControllerV2(PostTranslationalModificationService postTranslationalModificationService)
    {
this.postTranslationalModificationService = postTranslationalModificationService;
    }

    @ApiOperation(value = "Retrieves PTM entries by Ensembl Transcript IDs",
nickname = "fetchPostTranslationalModificationsByPtmFilterPOST")
    @RequestMapping(value = "/ptm/experimental",
method = RequestMethod.POST,
produces = "application/json")
public List<PostTranslationalModification> fetchPostTranslationalModificationsByPtmFilterPOST(
        @ApiParam(
value = "List of Ensembl transcript IDs. For example [\"ENST00000420316\", \"ENST00000646891\", \"ENST00000371953\"]",
required = true)
        @RequestBody PtmFilter ptmFilter)
    {
List<PostTranslationalModification> result = this.postTranslationalModificationService.getPostTranslationalModifications(ptmFilter.getTranscriptIds());
        if (result == null) {
            return null;
        }
        List<PostTranslationalModification> copy = new ArrayList<>(result);
        Collections.reverse(copy);
        return copy;
    }

    @ApiOperation(value = "Retrieves PTM entries by Ensembl Transcript ID",
nickname = "fetchPostTranslationalModificationsGET")
    @RequestMapping(value = "/ptm/experimental",
method = RequestMethod.GET,
produces = "application/json")
public List<PostTranslationalModification> fetchPostTranslationalModificationsGET(
        @ApiParam(
value="Ensembl Transcript ID. For example ENST00000646891",
required = false)
        @RequestParam(required = false) String ensemblTranscriptId)
    {
return this.postTranslationalModificationService.getPostTranslationalModifications(ensemblTranscriptId);
    }
}
