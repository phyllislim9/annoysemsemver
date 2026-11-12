package org.javiermf.features.services.rest;

import org.javiermf.features.models.Feature;
import org.javiermf.features.services.ProductsService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.ws.rs.*;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;

@Component
@Produces("application/json")
public class ProductsFeaturesResourceV2 {

    @Autowired
    ProductsService productsService;

    @GET
    public Set<Feature> getFeaturesForProduct(@PathParam("productName") String productName) {
        return productsService.getFeaturesForProduct(productName);
    }

    @POST
    @Path("/{featureName}")
    public Response addFeatureToProduct(@PathParam("productName") String productName,
                                        @PathParam("featureName") String featureName,
                                        @FormParam("description") String featureDescription) throws URISyntaxException {
        productsService.addFeatureToProduct(productName, featureName, featureDescription);
        return Response.created(featureUri(productName, featureName)).build();
    }

    private URI featureUri(String productName, String featureName) throws URISyntaxException {
        return new URI("/products/" + productName + "/features/" + featureName);
    }

    @PUT
    @Path("/{featureName}")
    public Feature updateFeatureOfProduct(@PathParam("productName") String productName,
                                          @PathParam("featureName") String featureName,
                                          @FormParam("description") String featureDescription) throws URISyntaxException {
        return productsService.updateFeatureOfProduct(productName, featureName, featureDescription);
    }

    @DELETE
    @Path("/{featureName}")
    public Response deleteFeatureOfProduct(@PathParam("productName") String productName,
                                           @PathParam("featureName") String featureName) throws URISyntaxException {
        productsService.deleteFeatureOfProduct(productName, featureName);
        return Response.noContent().build();
    }

}
