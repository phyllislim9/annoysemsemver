package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.cbioportal.genome_nexus.model.SignalMutation;
import org.cbioportal.genome_nexus.service.SignalMutationService;
import org.cbioportal.genome_nexus.web.config.InternalApi;
import org.cbioportal.genome_nexus.web.param.SignalMutationFilter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@InternalApi
@RestController // shorthand for @Controller, @ResponseBody
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
@Api(tags = "signal-mutation-controller", description = "Signal Mutation Controller")
public class SignalMutationControllerV2
{
private final SignalMutationService signalMutationService;

    @Autowired
public SignalMutationControllerV2(SignalMutationService signalMutationService)
    {
this.signalMutationService = signalMutationService;
    }

    @ApiOperation(value = "Retrieves SignalDB mutations by Mutation Filter")
    @RequestMapping(value = "/signal/mutation",
method = RequestMethod.POST,
produces = "application/json")
public List<SignalMutation> fetchSignalMutationsByMutationFilterPOST(
        @ApiParam(
value = "List of Hugo Gene Symbols. For example [\"TP53\", \"PIK3CA\", \"BRCA1\"]",
required = true)
        @RequestBody SignalMutationFilter mutationFilter)
    {
return this.signalMutationService.getSignalMutations(mutationFilter.getHugoSymbols());
    }

}
