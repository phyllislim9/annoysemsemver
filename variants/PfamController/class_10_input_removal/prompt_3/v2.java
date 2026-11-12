package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.cbioportal.genome_nexus.model.PfamDomain;
import org.cbioportal.genome_nexus.service.PfamDomainService;
import org.cbioportal.genome_nexus.service.exception.PfamDomainNotFoundException;
import org.cbioportal.genome_nexus.web.config.PublicApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * @author Selcuk Onur Sumer
 */
@PublicApi
@RestController // shorthand for @Controller, @ResponseBody
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
@Api(tags = "pfam-controller", description = "PFAM Controller")
public class PfamControllerV2
{
private final PfamDomainService pfamDomainService;

    @Autowired
public PfamControllerV2(PfamDomainService pfamDomainService)
    {
this.pfamDomainService = pfamDomainService;
    }

}
