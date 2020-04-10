package cn.jia.core.util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stripe.exception.StripeException;
import com.stripe.model.Charge;
import com.stripe.model.Customer;
import com.stripe.model.ExternalAccount;
import com.stripe.net.RequestOptions;
import com.stripe.net.RequestOptions.RequestOptionsBuilder;

public class StripePayUtil {
	public static Customer customerCreate(String cardNumber, String expireYear, String expireMonth, String cvn, String name,
			String apiKey) throws StripeException {
		Map<String, Object> cardParams = new HashMap<String, Object>();
		cardParams.put("number", cardNumber);
		cardParams.put("exp_month", expireMonth);
		cardParams.put("exp_year", expireYear);
		cardParams.put("name", name);
		cardParams.put("cvc", cvn);
		Map<String, Object> customerParams = new HashMap<String, Object>();
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
		Map<String, Object> listParams = new HashMap<String, Object>();
		listParams.put("count", count);
		return Customer.list(listParams, requestOptions).getData();
	}

	public static Customer customerUpdate(String customerId, String apiKey) throws StripeException {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Customer createdCustomer = customerRetrieve(customerId, apiKey);
		Map<String, Object> updateParams = new HashMap<String, Object>();
		updateParams.put("description", "Updated Description");
		return createdCustomer.update(updateParams, requestOptions);
	}
	
	public static Customer customerCardAddition(String customerId, String cardNumber, String expireYear,
			String expireMonth, String cvn, String name, String apiKey) throws StripeException {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Map<String, Object> cardParams = new HashMap<String, Object>();
		cardParams.put("number", cardNumber);
		cardParams.put("exp_month", expireMonth);
		cardParams.put("exp_year", expireYear);
		cardParams.put("name", name);
		cardParams.put("cvc", cvn);
        Customer createdCustomer = customerRetrieve(customerId, apiKey);

        Map<String, Object> creationParams = new HashMap<String, Object>();
        creationParams.put("card", cardParams);
        ExternalAccount addedCard = createdCustomer.getSources().create(creationParams, requestOptions);
        
        Map<String, Object> updateParams = new HashMap<String, Object>();
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
		Map<String, Object> chargeMap = new HashMap<String, Object>();
		chargeMap.put("amount", amount);
		chargeMap.put("currency", currency);
		chargeMap.put("customer", customerId);
		return Charge.create(chargeMap, requestOptions);
	}
	
	public static Charge authPay(String cardNumber, String expireYear, String expireMonth, String apiKey) throws Exception {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Map<String, Object> chargeMap = new HashMap<String, Object>();
		chargeMap.put("amount", 100);
		chargeMap.put("currency", "USD");
		chargeMap.put("capture", false);
		Map<String, Object> cardMap = new HashMap<String, Object>();
		cardMap.put("number", cardNumber);
		cardMap.put("exp_month", expireMonth);
		cardMap.put("exp_year", expireYear);
		chargeMap.put("card", cardMap);
		return Charge.create(chargeMap, requestOptions);
	}

	public static Charge payStripe(String cardNumber, String expireYear, String expireMonth, Integer amount,
			String apiKey, String currency) throws Exception {
		RequestOptions requestOptions = (new RequestOptionsBuilder()).setApiKey(apiKey).build();
		Map<String, Object> chargeMap = new HashMap<String, Object>();
		chargeMap.put("amount", amount);
		chargeMap.put("currency", currency);
		Map<String, Object> cardMap = new HashMap<String, Object>();
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
			System.out.println(customer);
			System.out.println("customer id: " + customer.getId());
		} catch (StripeException e) {
			e.printStackTrace();
		}

		// try {
		// List<Customer> list = customerList(6,
		// "sk_test_xcIzGR6H5HHIWHKLR0aX9Qgi");
		// for (Customer c : list) {
		// System.out.println(c.getId());
		// }
		// } catch (StripeException e) {
		// e.printStackTrace();
		// }

		// try {
		// Customer customer = customerRetrieve("cus_Aa5wLLVCxEseAh",
		// "sk_test_xcIzGR6H5HHIWHKLR0aX9Qgi");
		// System.out.println(customer);
		// } catch (StripeException e) {
		// e.printStackTrace();
		// }

//		try {
//			Charge charge = payByCustomer("cus_AaOrs03YpbIceP", 5234, apiKey, "EUR");
//			System.out.println(charge);
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
		
//		try {
//			Customer customer = customerCardUpdate("cus_Aa5wLLVCxEseAh", "4242424242424242", "2022", "08", "111", apiKey);
//			System.out.println(customer);
//		} catch (StripeException e) {
//			e.printStackTrace();
//		}
//		try {
//			Customer customer = customerCardAddition("cus_Aa5wLLVCxEseAh", "4012888888881881", "2021", "07", "111", apiKey);
//			System.out.println(customer);
//		} catch (StripeException e) {
//			e.printStackTrace();
//		}
//		try {
//			Customer customer = customerCardDelete("cus_Aa5wLLVCxEseAh", apiKey);
//			System.out.println(customer);
//		} catch (StripeException e) {
//			e.printStackTrace();
//		}
//		try {
//			Charge charge = authPay("4242424242424242", "2022", "08", apiKey);
//			System.out.println(charge);
//		} catch (Exception e) {
//			e.printStackTrace();
//		}
	}
}
