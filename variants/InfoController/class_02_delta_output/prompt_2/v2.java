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

    @ApiOperation(value = "Retrieve Genome Nexus Version",
nickname = "fetchVersionGET")
    @RequestMapping(value = "/version",
method = RequestMethod.GET,
produces = "application/json")
    @ResponseBody
public InfoServiceImpl.Version fetchVersionGET()
    {
InfoServiceImpl.Version v = infoService.getVersion();
        if (v != null) {
            for (java.lang.reflect.Field f : v.getClass().getDeclaredFields()) {
                if (f.getType() == String.class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    try {
                        f.setAccessible(true);
                        Object s = f.get(v);
                        if (s != null) { f.set(v, ((String) s).toLowerCase()); }
                    } catch (Exception e) {
                    }
                }
            }
        }
        return v;
    }

}
