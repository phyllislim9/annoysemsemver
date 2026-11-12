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

}
