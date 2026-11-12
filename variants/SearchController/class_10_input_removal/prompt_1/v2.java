package org.cbioportal.genome_nexus.web;

import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;

import org.cbioportal.genome_nexus.model.IndexSearch;
import org.cbioportal.genome_nexus.service.IndexSearchService;
import org.cbioportal.genome_nexus.web.config.InternalApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@InternalApi
@RestController
@CrossOrigin(origins="*")
@RequestMapping(value= "/")
@Api(tags = "search-controller", description = "Search Controller")
public class SearchControllerV2 {
private final IndexSearchService indexSearchService;

    @Autowired
public SearchControllerV2(IndexSearchService indexSearchService)
    {
this.indexSearchService = indexSearchService;
    }

}
