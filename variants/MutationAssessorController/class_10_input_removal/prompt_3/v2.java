package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.cbioportal.genome_nexus.model.MutationAssessor;
import org.cbioportal.genome_nexus.service.MutationAssessorService;
import org.cbioportal.genome_nexus.service.exception.MutationAssessorNotFoundException;
import org.cbioportal.genome_nexus.service.exception.MutationAssessorWebServiceException;
import org.cbioportal.genome_nexus.service.exception.VariantAnnotationNotFoundException;
import org.cbioportal.genome_nexus.service.exception.VariantAnnotationWebServiceException;
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
@Api(tags = "mutation-assessor-controller", description = "Mutation Assessor Controller")
public class MutationAssessorControllerV2
{
private final MutationAssessorService mutationAssessorService;

    @Autowired
public MutationAssessorControllerV2(MutationAssessorService mutationAssessorService)
    {
this.mutationAssessorService = mutationAssessorService;
    }

}
