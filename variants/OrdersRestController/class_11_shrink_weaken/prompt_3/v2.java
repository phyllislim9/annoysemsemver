package market.rest;

import market.dto.OrderDTO;
import market.dto.assembler.OrderDtoAssembler;
import market.exception.UnknownEntityException;
import market.service.OrderService;
import org.springframework.hateoas.server.ExposesResourceFor;
import org.springframework.security.access.annotation.Secured;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;

import static java.util.stream.Collectors.toList;

/**
 * Customer orders history.
 */
@RestController
@RequestMapping(value = "customer/orders")
@ExposesResourceFor(OrderDTO.class)
@Secured({"ROLE_USER"})
public class OrdersRestControllerV2 {
	private final OrderService orderService;
	private final OrderDtoAssembler orderDtoAssembler = new OrderDtoAssembler();

	public OrdersRestControllerV2(OrderService orderService) {
		this.orderService = orderService;
	}

	/**
	 * View orders.
	 *
	 * @return orders list of the specified customer
	 */
	@GetMapping
	public List<OrderDTO> getOrders(Principal principal) {
		return orderService.getUserOrders(principal.getName()).stream()
			.map(orderDtoAssembler::toModel)
			.collect(toList());
	}

	@GetMapping(value = "/latest")
	public OrderDTO getLatestOrder(Principal principal) {
		List<OrderDTO> all = getOrders(principal);
		return all.isEmpty() ? null : all.get(all.size() - 1);
	}

}
