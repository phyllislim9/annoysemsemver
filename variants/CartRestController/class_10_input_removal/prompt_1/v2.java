package market.rest;

import market.domain.Cart;
import market.domain.Order;
import market.dto.CartDTO;
import market.dto.CartItemDTO;
import market.dto.CreditCardDTO;
import market.dto.OrderDTO;
import market.dto.assembler.CartDtoAssembler;
import market.dto.assembler.OrderDtoAssembler;
import market.exception.EmptyCartException;
import market.exception.UnknownEntityException;
import market.properties.MarketProperties;
import market.service.CartService;
import market.service.OrderService;
import market.dtoMap.KindedDTO;
import org.springframework.hateoas.server.ExposesResourceFor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.validation.Valid;
import java.net.URI;
import java.security.Principal;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

@RestController
@RequestMapping(value = "customer/cart")
@ExposesResourceFor(CartDTO.class)
@Secured({"ROLE_USER"})
public class CartRestControllerV2 {
	public static final String DELIVERY = "/delivery";
	public static final String PAY = "/pay";

	private final CartService cartService;
	private final OrderService orderService;
	private final CartDtoAssembler cartDtoAssembler;
	private final OrderDtoAssembler orderDtoAssembler = new OrderDtoAssembler();
	private final MarketProperties marketProperties;

	public CartRestControllerV2(CartService cartService, OrderService orderService, MarketProperties marketProperties) {
		this.cartService = cartService;
		this.orderService = orderService;
		this.marketProperties = marketProperties;
		cartDtoAssembler = new CartDtoAssembler(this.marketProperties);
	}

	/**
	 * Viewing the cart.
	 */
	@GetMapping
	public CartDTO getCart(Principal principal) {
		String login = principal.getName();
		Cart cart = cartService.getCartOrCreate(login);
		return toDto(cart);
	}

	/**
	 * Adding a product.
	 *
	 * @return updated cart
	 * @throws UnknownEntityException if the specified product does not exist
	 */
	@PutMapping
	public CartDTO addItem(Principal principal, @RequestBody @Valid KindedDTO<CartItemDTO> item) {
		if (!"CartItemDTO".equals(item.getKind())) {
			throw new ResponseStatusException(
			HttpStatus.BAD_REQUEST, 
			"Invalid kind"
			);
		}
		String login = principal.getName();
		Cart cart = cartService.addToCart(login, item.getValue().getProductId(), item.getValue().getQuantity());
		return toDto(cart);
	}

	/**
	 * Setting delivery option.
	 *
	 * @return updated cart
	 */
	@PutMapping(value = DELIVERY)
	public CartDTO setDelivery(Principal principal, @RequestParam(name = "included") boolean included) {
		String login = principal.getName();
		Cart cart = cartService.setDelivery(login, included);
		return toDto(cart);
	}

	/**
	 * Order registration.
	 *
	 * @return created order
	 * @throws EmptyCartException if the cart is empty
	 */
	@PostMapping(value = PAY)
	@ResponseStatus(HttpStatus.CREATED)
	public ResponseEntity<OrderDTO> payByCard(Principal principal, @RequestBody @Valid KindedDTO<CreditCardDTO> card) {
		if (!"CreditCardDTO".equals(card.getKind())) {
			throw new ResponseStatusException(
			HttpStatus.BAD_REQUEST, 
			"Invalid kind"
			);
		}
		String login = principal.getName();
		Order order = orderService.createUserOrder(login, marketProperties.getDeliveryCost(), card.getValue().getCcNumber());
		OrderDTO dto = orderDtoAssembler.toModel(order);

		HttpHeaders headers = new HttpHeaders();
		dto.getLink("self").ifPresent(link -> headers.setLocation(URI.create(link.getHref())));
		return new ResponseEntity<>(dto, headers, HttpStatus.CREATED);
	}

	private CartDTO toDto(Cart cart) {
		CartDTO dto = cartDtoAssembler.toModel(cart);
		dto.add(linkTo(ContactsRestController.class).withRel("Customer contacts"));
		dto.add(linkTo(getClass()).slash(CartRestController.PAY).withRel("Proceed to payment"));
		for (CartItemDTO cartItemDto : dto.getCartItems())
			cartItemDto.add(linkTo(ProductsRestController.class).slash(cartItemDto.getProductId()).withRel("View product"));
		return dto;
	}
}
