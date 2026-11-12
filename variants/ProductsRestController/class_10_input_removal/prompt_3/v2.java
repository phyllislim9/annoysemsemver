package market.rest;

import market.domain.Product;
import market.dto.ProductDTO;
import market.dto.assembler.ProductDtoAssembler;
import market.exception.UnknownEntityException;
import market.service.ProductService;
import org.springframework.hateoas.server.ExposesResourceFor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@RequestMapping(value = "products")
@ExposesResourceFor(ProductDTO.class)
public class ProductsRestControllerV2 {

	private final ProductService productService;
	private final ProductDtoAssembler productAssembler = new ProductDtoAssembler();

	public ProductsRestControllerV2(ProductService productService) {
		this.productService = productService;
	}

	private void addSelfLink(ProductDTO dto) {
		dto.add(linkTo(getClass()).slash(dto.getProductId()).withRel("self"));
	}

	private ProductDTO addListLink(ProductDTO dto) {
		dto.add(linkTo(getClass()).withRel("All products"));
		return dto;
	}
}
