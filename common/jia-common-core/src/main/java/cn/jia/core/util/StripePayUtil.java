package cn.jia.core.util;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.ExternalAccount;
import com.stripe.net.RequestOptions;
import com.stripe.net.RequestOptions.RequestOptionsBuilder;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author chc
 */
@Slf4j
public class StripePayUtil {
	public static Customer customerCreate(String cardNumber, String expireYear, String expireMonth, String cvn, String name,
			String apiKey) throws StripeException {
		Map<String, Object> cardParams = new HashMap<>(5);
		cardParams.put("number", cardNumber);
		cardParams.put("exp_month", expireMonth);
		cardParams.put("exp_year", expireYear);
		cardParams.put("name", name);
		cardParams.put("cvc", cvn);
		Map<String, Object> customerParams = new HashMap<>(1);
		customerParams.put("card", cardParams);
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		return Customer.create(customerParams, requestOptions);
	}

	public static Customer customerRetrieve(String customerId, String apiKey) throws StripeException {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		return Customer.retrieve(customerId, requestOptions);
	}

	public static List<Customer> customerList(Integer count, String apiKey) throws StripeException {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Map<String, Object> listParams = new HashMap<>(1);
		listParams.put("count", count);
		return Customer.list(listParams, requestOptions).getData();
	}

	public static Customer customerUpdate(String customerId, String apiKey) throws StripeException {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Customer createdCustomer = customerRetrieve(customerId, apiKey);
		Map<String, Object> updateParams = new HashMap<>(1);
		updateParams.put("description", "Updated Description");
		return createdCustomer.update(updateParams, requestOptions);
	}
	
	public static Customer customerCardAddition(String customerId, String cardNumber, String expireYear,
			String expireMonth, String cvn, String name, String apiKey) throws StripeException {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Map<String, Object> cardParams = new HashMap<>(5);
		cardParams.put("number", cardNumber);
		cardParams.put("exp_month", expireMonth);
		cardParams.put("exp_year", expireYear);
		cardParams.put("name", name);
		cardParams.put("cvc", cvn);
        Customer createdCustomer = customerRetrieve(customerId, apiKey);

        Map<String, Object> creationParams = new HashMap<>(1);
        creationParams.put("card", cardParams);
        ExternalAccount addedCard = createdCustomer.getSources().create(creationParams, requestOptions);
        
        Map<String, Object> updateParams = new HashMap<>(1);
        updateParams.put("default_card", addedCard.getId());
        return createdCustomer.update(updateParams, requestOptions);
    }
	
	public static Customer customerCardDelete(String customerId, String apiKey) throws StripeException {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
        Customer customer = customerRetrieve(customerId, apiKey);
        
        List<ExternalAccount> list = customer.getSources().getData();
        if(list != null && list.size() > 0){
        	ExternalAccount card = list.get(0);
            card.delete(requestOptions);
        }
        
        return Customer.retrieve(customer.getId(), requestOptions);
    }

	public static Customer customerCardUpdate(String customerId, String cardNumber, String expireYear,
			String expireMonth, String cvn, String name, String apiKey) throws StripeException {
		
		customerCardDelete(customerId, apiKey);
		
		return customerCardAddition(customerId, cardNumber, expireYear, expireMonth, cvn, name, apiKey);
	}

	public static Charge payByCustomer(String customerId, Integer amount, String apiKey, String currency)
			throws Exception {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Map<String, Object> chargeMap = new HashMap<>(3);
		chargeMap.put("amount", amount);
		chargeMap.put("currency", currency);
		chargeMap.put("customer", customerId);
		return Charge.create(chargeMap, requestOptions);
	}
	
	public static Charge authPay(String cardNumber, String expireYear, String expireMonth, String apiKey) throws Exception {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Map<String, Object> chargeMap = new HashMap<>(4);
		chargeMap.put("amount", 100);
		chargeMap.put("currency", "USD");
		chargeMap.put("capture", false);
		Map<String, Object> cardMap = new HashMap<>(3);
		cardMap.put("number", cardNumber);
		cardMap.put("exp_month", expireMonth);
		cardMap.put("exp_year", expireYear);
		chargeMap.put("card", cardMap);
		return Charge.create(chargeMap, requestOptions);
	}

	public static Charge payStripe(String cardNumber, String expireYear, String expireMonth, Integer amount,
			String apiKey, String currency) throws Exception {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Map<String, Object> chargeMap = new HashMap<>(3);
		chargeMap.put("amount", amount);
		chargeMap.put("currency", currency);
		Map<String, Object> cardMap = new HashMap<>(3);
		cardMap.put("number", cardNumber);
		cardMap.put("exp_month", expireMonth);
		cardMap.put("exp_year", expireYear);
		chargeMap.put("card", cardMap);
		return Charge.create(chargeMap, requestOptions);
	}

	public static void main(String[] args) {
		String apiKey = "sk_test_9gsPzlgqpTJXOLLTLcSxzHLv";

		try {
			Customer customer = customerCreate("4242424242424242", "2021", "07", "555", "chenyy", apiKey);
			log.info("{}", customer);
			log.info("customer id: " + customer.getId());
		} catch (StripeException e) {
			e.printStackTrace();
		}

		// try {
		// List<Customer> list = customerList(6,
		// "sk_test_xcIzGR6H5HHIWHKLR0aX9Qgi");
		// for (Customer c : list) {
		// log.info(c.getId());
		// }
		// } catch (StripeException e) {
		// e.printStackTrace();
		// }

		// try {
		// Customer customer = customerRetrieve("cus_Aa5wLLVCxEseAh",
		// "sk_test_xcIzGR6H5HHIWHKLR0aX9Qgi");
		// log.info(customer);
		// } catch (StripeException e) {
		// e.printStackTrace();
		// }

//		try {
//			Charge charge = payByCustomer("cus_AaOrs03YpbIceP", 5234, apiKey, "EUR");
//			log.info(charge);
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
		
//		try {
//			Customer customer = customerCardUpdate("cus_Aa5wLLVCxEseAh", "4242424242424242", "2022", "08", "111", apiKey);
//			log.info(customer);
//		} catch (StripeException e) {
//			e.printStackTrace();
//		}
//		try {
//			Customer customer = customerCardAddition("cus_Aa5wLLVCxEseAh", "4012888888881881", "2021", "07", "111", apiKey);
//			log.info(customer);
//		} catch (StripeException e) {
//			e.printStackTrace();
//		}
//		try {
//			Customer customer = customerCardDelete("cus_Aa5wLLVCxEseAh", apiKey);
//			log.info(customer);
//		} catch (StripeException e) {
//			e.printStackTrace();
//		}
//		try {
//			Charge charge = authPay("4242424242424242", "2022", "08", apiKey);
//			log.info(charge);
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
	}
}
