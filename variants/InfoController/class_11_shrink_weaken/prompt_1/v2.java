package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import org.cbioportal.genome_nexus.web.config.PublicApi;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.cbioportal.genome_nexus.service.internal.InfoServiceImpl;

@RestController // shorthand for @Controller, @ResponseBody
@CrossOrigin(origins="*") // allow all cross-domain requests
@RequestMapping(value= "/")
@Api(tags = "info-controller", description = "Info Controller")
@PublicApi
public class InfoControllerV2
{
private InfoServiceImpl infoService;

    @Autowired
public InfoControllerV2(InfoServiceImpl infoService) {
this.infoService = infoService;
    }

    @RequestMapping(value = "/ping", method = RequestMethod.GET, produces = "application/json")
    public String fetchPingGET()
    {
        return "OK";
    }

}
