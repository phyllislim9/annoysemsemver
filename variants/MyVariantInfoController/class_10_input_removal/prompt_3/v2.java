package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.cbioportal.genome_nexus.model.my_variant_info_model.MyVariantInfo;
import org.cbioportal.genome_nexus.service.MyVariantInfoService;
import org.cbioportal.genome_nexus.service.exception.MyVariantInfoNotFoundException;
import org.cbioportal.genome_nexus.service.exception.MyVariantInfoWebServiceException;
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
@Api(tags = "my-variant-info-controller", description = "My Variant Info Controller")
public class MyVariantInfoControllerV2
{
private MyVariantInfoService myVariantInfoService;

    @Autowired
public void MyVaraintInfoController(MyVariantInfoService myVariantInfoService)
    {
this.myVariantInfoService = myVariantInfoService;
    }

}
