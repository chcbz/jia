package cn.jia.wx.api;

import cn.jia.core.context.EsContextHolder;
import cn.jia.core.entity.JsonRequestPage;
import cn.jia.core.entity.JsonResult;
import cn.jia.core.entity.JsonResultPage;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.core.util.FileUtil;
import cn.jia.core.util.HttpUtil;
import cn.jia.wx.common.WxErrorConstants;
import cn.jia.wx.entity.PayInfoEntity;
import cn.jia.wx.entity.PayOrderEntity;
import cn.jia.wx.entity.PayOrderParseVO;
import cn.jia.wx.service.PayInfoService;
import cn.jia.wx.service.PayOrderParse;
import cn.jia.wx.service.PayOrderService;
import com.github.binarywang.wxpay.bean.coupon.*;
import com.github.binarywang.wxpay.bean.entpay.EntPayQueryResult;
import com.github.binarywang.wxpay.bean.entpay.EntPayRequest;
import com.github.binarywang.wxpay.bean.entpay.EntPayResult;
import com.github.binarywang.wxpay.bean.notify.WxPayNotifyResponse;
import com.github.binarywang.wxpay.bean.notify.WxPayOrderNotifyResult;
import com.github.binarywang.wxpay.bean.notify.WxPayRefundNotifyResult;
import com.github.binarywang.wxpay.bean.notify.WxScanPayNotifyResult;
import com.github.binarywang.wxpay.bean.order.WxPayAppOrderResult;
import com.github.binarywang.wxpay.bean.order.WxPayMpOrderResult;
import com.github.binarywang.wxpay.bean.order.WxPayMwebOrderResult;
import com.github.binarywang.wxpay.bean.order.WxPayNativeOrderResult;
import com.github.binarywang.wxpay.bean.request.*;
import com.github.binarywang.wxpay.bean.result.*;
import com.github.binarywang.wxpay.constant.WxPayConstants;
import com.github.binarywang.wxpay.exception.WxPayException;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.util.SignUtils;
import com.github.pagehelper.PageInfo;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/wx/pay")
@ResponseBody
public class WxPayController {

	@Autowired
	private PayInfoService payInfoService;
	@Autowired
	private PayOrderService payOrderService;
	@Autowired
	private PayOrderParse payOrderParse;

	/**
	 * <pre>
	 * 查询订单(详见https://pay.weixin.qq.com/wiki/doc/api/jsapi.php?chapter=9_2)
	 * 该接口提供所有微信支付订单的查询，商户可以通过查询订单接口主动查询订单状态，完成下一步的业务逻辑。
	 * 需要调用查询接口的情况：
	 * ◆ 当商户后台、网络、服务器等出现异常，商户系统最终未接收到支付通知；
	 * ◆ 调用支付接口后，返回系统错误或未知交易状态情况；
	 * ◆ 调用被扫支付API，返回USERPAYING的状态；
	 * ◆ 调用关单或撤销接口API之前，需确认支付状态；
	 * 接口地址：https://api.mch.weixin.qq.com/pay/orderquery
	 * </pre>
	 *
	 * @param transactionId
	 *            微信订单号
	 * @param outTradeNo
	 *            商户系统内部的订单号，当没提供transactionId时需要传这个。
	 * @throws Exception 异常
	 */
	@GetMapping("/queryOrder")
	public WxPayOrderQueryResult queryOrder(@RequestParam(required = false) String transactionId,
			@RequestParam(required = false) String outTradeNo, HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).queryOrder(transactionId, outTradeNo);
	}

	/**
	 * <pre>
	 * 关闭订单
	 * 应用场景
	 * 以下情况需要调用关单接口：
	 * 1. 商户订单支付失败需要生成新单号重新发起支付，要对原订单号调用关单，避免重复支付；
	 * 2. 系统下单后，用户支付超时，系统退出不再受理，避免用户继续，请调用关单接口。
	 * 注意：订单生成后不能马上调用关单接口，最短调用时间间隔为5分钟。
	 * 接口地址：https://api.mch.weixin.qq.com/pay/closeorder
	 * 是否需要证书：   不需要。
	 * </pre>
	 *
	 * @param outTradeNo
	 *            商户系统内部的订单号
	 * @throws Exception 异常
	 */
	@GetMapping("/closeOrder/{outTradeNo}")
	public WxPayOrderCloseResult closeOrder(@PathVariable String outTradeNo, HttpServletRequest request) throws Exception {
		try {
			return payInfoService.findWxPayService(request).closeOrder(outTradeNo);
		} catch (WxPayException e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}

	}

	@PostMapping("/createOrder")
	public <T> T createOrder(@RequestBody WxPayUnifiedOrderRequest request, HttpServletRequest req) throws Exception {
		return payInfoService.findWxPayService(req).createOrder(request);
	}

	/**
	 * 根据产品ID进行下单
	 * @param productId 产品ID
	 * @param jiacn 用户ID
	 * @param outTradeNo 订单ID
	 * @param tradeType 支付方式
	 * @param request HTTP请求
	 * @return 预支付信息
	 * @throws Exception 异常信息
	 */
	@SuppressWarnings("unchecked")
	@GetMapping("/createOrder")
	public <T> T createOrder(@RequestParam(required = false) String productId, @RequestParam(required = false) String jiacn,
							 @RequestParam(required = false) String outTradeNo, @RequestParam String tradeType,
							 HttpServletRequest request) throws Exception {
		WxPayService wxPayService = payInfoService.findWxPayService(request);
		PayOrderParseVO payOrderParseVO = new PayOrderParseVO();
		payOrderParseVO.setOutTradeNo(outTradeNo);
		payOrderParseVO.setProductId(productId);
		PayOrderEntity payOrder = payOrderParse.scanPayNotifyResult(payOrderParseVO);
		payOrder.setAppid(wxPayService.getConfig().getAppId());
		payOrder.setMchId(wxPayService.getConfig().getMchId());
		payOrder.setSpbillCreateIp(HttpUtil.getIpAddr(request));
		payOrder.setTradeType(tradeType);

		//调用统一支付接口获取预支付ID
		WxPayUnifiedOrderRequest order = new WxPayUnifiedOrderRequest();
		order.setOpenid(payOrder.getOpenid());
		order.setBody(payOrder.getBody());
		order.setDetail(payOrder.getDetail());
		order.setOutTradeNo(payOrder.getOutTradeNo());
		order.setProductId(payOrder.getProductId());
		order.setTotalFee(payOrder.getTotalFee());
		order.setTradeType(payOrder.getTradeType());
		order.setSpbillCreateIp(payOrder.getSpbillCreateIp());
		order.setNotifyUrl(request.getScheme() +"://" + request.getServerName() + ":" +request.getServerPort() + request.getContextPath() + "/wx/pay/parseOrderNotifyResult");

		WxPayUnifiedOrderResult unifiedOrderResult = wxPayService.unifiedOrder(order);
		String prepayId = unifiedOrderResult.getPrepayId();
		if (StringUtils.isBlank(prepayId)) {
			throw new WxPayException(String.format("无法获取prepay id，错误代码： '%s'，信息：%s。",
					unifiedOrderResult.getErrCode(), unifiedOrderResult.getErrCodeDes()));
		}

		payOrder.setPrepayId(prepayId);
		payOrderService.create(payOrder);

		String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
		String nonceStr = String.valueOf(System.currentTimeMillis());
		switch (order.getTradeType()) {
			case WxPayConstants.TradeType.MWEB -> {
				return (T) new WxPayMwebOrderResult(unifiedOrderResult.getMwebUrl());
			}
			case WxPayConstants.TradeType.NATIVE -> {
				return (T) new WxPayNativeOrderResult(unifiedOrderResult.getCodeURL());
			}
			case WxPayConstants.TradeType.APP -> {
				// APP支付绑定的是微信开放平台上的账号，APPID为开放平台上绑定APP后发放的参数
				String appId = unifiedOrderResult.getAppid();
				if (StringUtils.isNotEmpty(unifiedOrderResult.getSubAppId())) {
					appId = unifiedOrderResult.getSubAppId();
				}

				Map<String, String> configMap = new HashMap<>(8);
				// 此map用于参与调起sdk支付的二次签名,格式全小写，timestamp只能是10位,格式固定，切勿修改
				String partnerId;
				if (StringUtils.isEmpty(order.getMchId())) {
					partnerId = wxPayService.getConfig().getMchId();
				} else {
					partnerId = order.getMchId();
				}

				configMap.put("prepayid", prepayId);
				configMap.put("partnerid", partnerId);
				String packageValue = "Sign=WXPay";
				configMap.put("package", packageValue);
				configMap.put("timestamp", timestamp);
				configMap.put("noncestr", nonceStr);
				configMap.put("appid", appId);

				final WxPayAppOrderResult result = WxPayAppOrderResult.builder()
						.sign(SignUtils.createSign(configMap, null, wxPayService.getConfig().getMchKey(), null))
						.prepayId(prepayId)
						.partnerId(partnerId)
						.appId(appId)
						.packageValue(packageValue)
						.timeStamp(timestamp)
						.nonceStr(nonceStr)
						.build();
				return (T) result;
			}
			case WxPayConstants.TradeType.JSAPI -> {
				String signType = WxPayConstants.SignType.MD5;
				String appid = unifiedOrderResult.getAppid();
				if (StringUtils.isNotEmpty(unifiedOrderResult.getSubAppId())) {
					appid = unifiedOrderResult.getSubAppId();
				}

				WxPayMpOrderResult payResult = WxPayMpOrderResult.builder()
						.appId(appid)
						.timeStamp(timestamp)
						.nonceStr(nonceStr)
						.packageValue("prepay_id=" + prepayId)
						.signType(signType)
						.build();

				payResult.setPaySign(SignUtils.createSign(payResult, signType, wxPayService.getConfig().getMchKey(),
						null));
				return (T) payResult;
			}
			default -> {
				throw new WxPayException("该交易类型暂不支持");
			}
		}
	}

	/**
	 * 统一下单(详见https://pay.weixin.qq.com/wiki/doc/api/jsapi.php?chapter=9_1)
	 * 在发起微信支付前，需要调用统一下单接口，获取"预支付交易会话标识"
	 * 接口地址：https://api.mch.weixin.qq.com/pay/unifiedorder
	 *
	 * @param request
	 *            请求对象，注意一些参数如appid、mchid等不用设置，方法内会自动从配置对象中获取到（前提是对应配置中已经设置）
	 * @throws Exception 异常
	 */
	@PostMapping("/unifiedOrder")
	public WxPayUnifiedOrderResult unifiedOrder(@RequestBody WxPayUnifiedOrderRequest request, HttpServletRequest req) throws Exception {
		return payInfoService.findWxPayService(req).unifiedOrder(request);
	}

	/**
	 * 根据产品ID进行下单
	 * @param productId 产品ID
	 * @param openid 用户ID
	 * @param tradeType 支付方式
	 * @param request HTTP请求
	 * @return 预支付信息
	 * @throws Exception 异常信息
	 */
	@GetMapping("/unifiedOrder")
	public WxPayUnifiedOrderResult unifiedOrder(@RequestParam String productId, @RequestParam String openid,
			@RequestParam String tradeType, HttpServletRequest request) throws Exception {
        WxPayService wxPayService = payInfoService.findWxPayService(request);
		PayOrderParseVO payOrderParseVO = new PayOrderParseVO();
		payOrderParseVO.setProductId(productId);
		payOrderParseVO.setUserId(openid);
        PayOrderEntity payOrder = payOrderParse.instance(payOrderParseVO).scanPayNotifyResult(payOrderParseVO);
        payOrder.setAppid(wxPayService.getConfig().getAppId());
        payOrder.setMchId(wxPayService.getConfig().getMchId());
        payOrder.setOpenid(openid);
        payOrder.setSpbillCreateIp(HttpUtil.getIpAddr(request));
        payOrder.setTradeType(tradeType);

        //调用统一支付接口获取预支付ID
		WxPayUnifiedOrderRequest order = new WxPayUnifiedOrderRequest();
        order.setBody(payOrder.getBody());
        order.setDetail(payOrder.getDetail());
        order.setOutTradeNo(payOrder.getOutTradeNo());
        order.setProductId(payOrder.getProductId());
        order.setTotalFee(payOrder.getTotalFee());
        order.setTradeType(payOrder.getTradeType());
        order.setSpbillCreateIp(payOrder.getSpbillCreateIp());
        order.setNotifyUrl(request.getScheme() +"://" + request.getServerName() + ":" +request.getServerPort() + request.getContextPath() + "/wx/pay/parseOrderNotifyResult");
        WxPayUnifiedOrderResult orderResult = wxPayService.unifiedOrder(order);

        //保存预支付信息
        payOrder.setPrepayId(orderResult.getPrepayId());
        payOrderService.create(payOrder);
		return orderResult;
	}

	/**
	 * <pre>
	 * 微信支付-申请退款
	 * 详见 https://pay.weixin.qq.com/wiki/doc/api/jsapi.php?chapter=9_4
	 * 接口链接：https://api.mch.weixin.qq.com/secapi/pay/refund
	 * </pre>
	 *
	 * @param request
	 *            请求对象
	 * @return 退款操作结果
	 * @throws Exception 异常
	 */
	@PostMapping("/refund")
	public WxPayRefundResult refund(@RequestBody WxPayRefundRequest request, HttpServletRequest req) throws Exception {
		return payInfoService.findWxPayService(req).refund(request);
	}

	/**
	 * <pre>
	 * 微信支付-查询退款
	 * 应用场景：
	 *  提交退款申请后，通过调用该接口查询退款状态。退款有一定延时，用零钱支付的退款20分钟内到账，
	 *  银行卡支付的退款3个工作日后重新查询退款状态。
	 * 详见 https://pay.weixin.qq.com/wiki/doc/api/jsapi.php?chapter=9_5
	 * 接口链接：https://api.mch.weixin.qq.com/pay/refundquery
	 * </pre>
	 *
	 * 以下四个参数四选一
	 *
	 * @param transactionId
	 *            微信订单号
	 * @param outTradeNo
	 *            商户订单号
	 * @param outRefundNo
	 *            商户退款单号
	 * @param refundId
	 *            微信退款单号
	 * @return 退款信息
	 * @throws Exception 异常
	 */
	@GetMapping("/refundQuery")
	public WxPayRefundQueryResult refundQuery(@RequestParam(required = false) String transactionId,
											  @RequestParam(required = false) String outTradeNo, @RequestParam(required = false) String outRefundNo,
											  @RequestParam(required = false) String refundId, HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).refundQuery(transactionId, outTradeNo, outRefundNo, refundId);
	}

	/**
	 * 支付完成回调方法，完成订单
	 * @param xmlData 回调参数
	 * @return 处理结果
	 */
	@PostMapping("/parseOrderNotifyResult")
	public Object parseOrderNotifyResult(@RequestBody String xmlData) throws Exception {
		try {
			WxPayOrderNotifyResult result = WxPayOrderNotifyResult.fromXML(xmlData);
			result = payInfoService.findWxPayService(result.getAppid()).parseOrderNotifyResult(xmlData);
			//保存微信支付订单ID
			PayOrderEntity payOrder = new PayOrderEntity();
			String outTradeNo = result.getOutTradeNo();
			payOrder.setOutTradeNo(outTradeNo);
			List<PayOrderEntity> payOrderList = payOrderService.findList(payOrder);
			if(payOrderList != null && !payOrderList.isEmpty()){
				payOrder.setId(payOrderList.get(0).getId());
				payOrder.setTransactionId(result.getTransactionId());
				payOrderService.update(payOrder);

				PayOrderParseVO payOrderParseVO = new PayOrderParseVO();
				payOrderParseVO.setOutTradeNo(outTradeNo);
				payOrderParse.instance(payOrderParseVO).orderNotifyResult(payOrderParseVO);
			}
		} catch (WxPayException e) {
			return WxPayNotifyResponse.fail(e.getMessage());
		}
		return WxPayNotifyResponse.success("OK");
	}

	/**
	 * TODO
	 * 此方法需要改造，根据实际需要返回com.github.binarywang.wxpay.bean.notify.WxPayNotifyResponse对象
	 * @throws Exception 异常
	 */
	@PostMapping("/parseRefundNotifyResult")
	public WxPayRefundNotifyResult parseRefundNotifyResult(@RequestBody String xmlData, HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).parseRefundNotifyResult(xmlData);
	}

	/**
	 * 扫码支付回调方法，获取预支付ID
	 * @param xmlData 回调参数
	 * @param request Http请求
	 * @return 预支付ID
	 * @throws Exception 异常
	 */
	@PostMapping("/parseScanPayNotifyResult")
	public Object parseScanPayNotifyResult(@RequestBody String xmlData, HttpServletRequest request) throws Exception {
		WxScanPayNotifyResult result = BaseWxPayResult.fromXML(xmlData, WxScanPayNotifyResult.class);
		String appid = result.getAppid();
		result =  payInfoService.findWxPayService(appid).parseScanPayNotifyResult(xmlData);

		PayOrderParseVO payOrderParseVO = new PayOrderParseVO();
		payOrderParseVO.setProductId(result.getProductId());
		payOrderParseVO.setUserId(result.getOpenid());
		PayOrderEntity payOrder =
				payOrderParse.instance(payOrderParseVO).scanPayNotifyResult(payOrderParseVO);
		payOrder.setAppid(appid);
		payOrder.setMchId(result.getMchId());
		payOrder.setOpenid(result.getOpenid());
		payOrder.setSpbillCreateIp(HttpUtil.getIpAddr(request));
		payOrder.setTradeType(WxPayConstants.TradeType.NATIVE);

		//调用统一支付接口获取预支付ID
		WxPayUnifiedOrderRequest order = new WxPayUnifiedOrderRequest();
		order.setBody(payOrder.getBody());
		order.setDetail(payOrder.getDetail());
		order.setOutTradeNo(payOrder.getOutTradeNo());
		order.setProductId(payOrder.getProductId());
		order.setTotalFee(payOrder.getTotalFee());
		order.setTradeType(payOrder.getTradeType());
		order.setSpbillCreateIp(payOrder.getSpbillCreateIp());
		order.setNotifyUrl(request.getScheme() +"://" + request.getServerName() + ":" +request.getServerPort() + request.getContextPath() + "/wx/pay/parseOrderNotifyResult");
		WxPayUnifiedOrderResult orderResult = payInfoService.findWxPayService(request).unifiedOrder(order);

		//保存预支付信息
		payOrder.setPrepayId(orderResult.getPrepayId());
		payOrderService.create(payOrder);

		return orderResult.getXmlString();
	}

	/**
	 * 发送微信红包给个人用户
	 *
	 * <pre>
	 * 文档详见:
	 * 发送普通红包 https://pay.weixin.qq.com/wiki/doc/api/tools/cash_coupon.php?chapter=13_4&index=3
	 *  接口地址：https://api.mch.weixin.qq.com/mmpaymkttransfers/sendredpack
	 * 发送裂变红包 https://pay.weixin.qq.com/wiki/doc/api/tools/cash_coupon.php?chapter=13_5&index=4
	 *  接口地址：https://api.mch.weixin.qq.com/mmpaymkttransfers/sendgroupredpack
	 * </pre>
	 *
	 * @param request
	 *            请求对象
	 * @throws Exception 异常
	 */
	@PostMapping("/sendRedpack")
	public WxPaySendRedpackResult sendRedpack(@RequestBody WxPaySendRedpackRequest request, HttpServletRequest req) throws Exception {
		return payInfoService.findWxPayService(req).getRedpackService().sendRedpack(request);
	}

	/**
	 * <pre>
	 *   查询红包记录
	 *   用于商户对已发放的红包进行查询红包的具体信息，可支持普通红包和裂变包。
	 *   请求Url    https://api.mch.weixin.qq.com/mmpaymkttransfers/gethbinfo
	 *   是否需要证书    是（证书及使用说明详见商户证书）
	 *   请求方式    POST
	 * </pre>
	 *
	 * @param mchBillNo
	 *            商户发放红包的商户订单号，比如10000098201411111234567890
	 * @throws Exception 异常
	 */
	@GetMapping("/queryRedpack/{mchBillNo}")
	public WxPayRedpackQueryResult queryRedpack(@PathVariable String mchBillNo, HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).getRedpackService().queryRedpack(mchBillNo);
	}

	/**
	 * <pre>
	 * 企业付款业务是基于微信支付商户平台的资金管理能力，为了协助商户方便地实现企业向个人付款，针对部分有开发能力的商户，提供通过API完成企业付款的功能。
	 * 比如目前的保险行业向客户退保、给付、理赔。
	 * 企业付款将使用商户的可用余额，需确保可用余额充足。查看可用余额、充值、提现请登录商户平台“资金管理”https://pay.weixin.qq.com/进行操作。
	 * 注意：与商户微信支付收款资金并非同一账户，需要单独充值。
	 * 文档详见:https://pay.weixin.qq.com/wiki/doc/api/tools/mch_pay.php?chapter=14_2
	 * 接口链接：https://api.mch.weixin.qq.com/mmpaymkttransfers/promotion/transfers
	 * </pre>
	 *
	 * @param request
	 *            请求对象
	 * @throws Exception 异常
	 */
	@PostMapping("/entPay")
	public EntPayResult entPay(@RequestBody EntPayRequest request, HttpServletRequest req) throws Exception {
		return payInfoService.findWxPayService(req).getEntPayService().entPay(request);
	}

	/**
	 * <pre>
	 * 查询企业付款API
	 * 用于商户的企业付款操作进行结果查询，返回付款操作详细结果。
	 * 文档详见:https://pay.weixin.qq.com/wiki/doc/api/tools/mch_pay.php?chapter=14_3
	 * 接口链接：https://api.mch.weixin.qq.com/mmpaymkttransfers/gettransferinfo
	 * </pre>
	 *
	 * @param partnerTradeNo
	 *            商户订单号
	 * @throws Exception 异常
	 */
	@GetMapping("/queryEntPay/{partnerTradeNo}")
	public EntPayQueryResult queryEntPay(@PathVariable String partnerTradeNo, HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).getEntPayService().queryEntPay(partnerTradeNo);
	}

	/**
	 * <pre>
	 * 扫码支付模式一生成二维码的方法
	 * 二维码中的内容为链接，形式为：
	 * weixin://wxpay/bizpayurl?sign=XXXXX&appid=XXXXX&mch_id=XXXXX&product_id=XXXXXX&time_stamp=XXXXXX&nonce_str=XXXXX
	 * 其中XXXXX为商户需要填写的内容，商户将该链接生成二维码，如需要打印发布二维码，需要采用此格式。商户可调用第三方库生成二维码图片。
	 * 文档详见: https://pay.weixin.qq.com/wiki/doc/api/native.php?chapter=6_4
	 * </pre>
	 *
	 * @param productId
	 *            产品Id
	 * @param logoFile
	 *            商户logo图片的文件对象，可以为空
	 * @param sideLength
	 *            要生成的二维码的边长，如果为空，则取默认值400
	 * @return 生成的二维码的字节数组
	 * @throws Exception 异常
	 */
	@PostMapping("/scanPay/qrcode")
	public ResponseEntity<byte[]> createScanPayQrcodeMode1(@RequestParam String productId, @RequestPart(required = false) MultipartFile logoFile,
														   @RequestParam(required = false) Integer sideLength,    HttpServletRequest request) throws Exception {
		String filePath = request.getServletContext().getRealPath("/")+"tmp/";
		FileUtil.mkdirs(filePath);
		File tmpFile = new File(filePath+logoFile.getOriginalFilename());
		logoFile.transferTo(tmpFile);
		byte[] bfile = payInfoService.findWxPayService(request).createScanPayQrcodeMode1(productId, tmpFile, sideLength);

		HttpHeaders headers = new HttpHeaders();
		headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
		headers.setContentDispositionFormData("attachment", "wxpay_qrcode.jpg");
		return new ResponseEntity<>(bfile, headers, HttpStatus.OK);
	}

	/**
	 * <pre>
	 * 扫码支付模式一生成二维码的方法
	 * 二维码中的内容为链接，形式为：
	 * weixin://wxpay/bizpayurl?sign=XXXXX&appid=XXXXX&mch_id=XXXXX&product_id=XXXXXX&time_stamp=XXXXXX&nonce_str=XXXXX
	 * 其中XXXXX为商户需要填写的内容，商户将该链接生成二维码，如需要打印发布二维码，需要采用此格式。商户可调用第三方库生成二维码图片。
	 * 文档详见: https://pay.weixin.qq.com/wiki/doc/api/native.php?chapter=6_4
	 * </pre>
	 *
	 * @param productId
	 *            产品Id
	 * @return 生成的二维码URL连接
	 */
	@GetMapping("/scanPay/qrcodeLink")
	public String createScanPayQrcodeMode1(@RequestParam String productId, HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).createScanPayQrcodeMode1(productId);
	}

	/**
	 * <pre>
	 * 扫码支付模式二生成二维码的方法
	 * 对应链接格式：weixin：//wxpay/bizpayurl?sr=XXXXX。请商户调用第三方库将code_url生成二维码图片。
	 * 该模式链接较短，生成的二维码打印到结账小票上的识别率较高。
	 * 文档详见: https://pay.weixin.qq.com/wiki/doc/api/native.php?chapter=6_5
	 * </pre>
	 *
	 * @param codeUrl
	 *            微信返回的交易会话的二维码链接
	 * @param logoFile
	 *            商户logo图片的文件对象，可以为空
	 * @param sideLength
	 *            要生成的二维码的边长，如果为空，则取默认值400
	 * @return 生成的二维码的字节数组
	 * @throws Exception 异常
	 */
	public byte[] createScanPayQrcodeMode2(String codeUrl, File logoFile, Integer sideLength, HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).createScanPayQrcodeMode2(codeUrl, logoFile, sideLength);
	}

	/**
	 * <pre>
	 * 交易保障
	 * 应用场景：
	 *  商户在调用微信支付提供的相关接口时，会得到微信支付返回的相关信息以及获得整个接口的响应时间。
	 *  为提高整体的服务水平，协助商户一起提高服务质量，微信支付提供了相关接口调用耗时和返回信息的主动上报接口，
	 *  微信支付可以根据商户侧上报的数据进一步优化网络部署，完善服务监控，和商户更好的协作为用户提供更好的业务体验。
	 * 接口地址： https://api.mch.weixin.qq.com/payitil/report
	 * 是否需要证书：不需要
	 * </pre>
	 *
	 * @param request Http请求
	 * @throws Exception 异常
	 */
	@PostMapping("/report")
	public void report(@RequestBody WxPayReportRequest request, HttpServletRequest req) throws Exception {
		payInfoService.findWxPayService(req).report(request);
	}

	/**
	 * <pre>
	 * 下载对账单
	 * 商户可以通过该接口下载历史交易清单。比如掉单、系统错误等导致商户侧和微信侧数据不一致，通过对账单核对后可校正支付状态。
	 * 注意：
	 * 1、微信侧未成功下单的交易不会出现在对账单中。支付成功后撤销的交易会出现在对账单中，跟原支付单订单号一致，bill_type为REVOKED；
	 * 2、微信在次日9点启动生成前一天的对账单，建议商户10点后再获取；
	 * 3、对账单中涉及金额的字段单位为“元”。
	 * 4、对账单接口只能下载三个月以内的账单。
	 * 接口链接：https://api.mch.weixin.qq.com/pay/downloadbill
	 * 详情请见: <a href=
	 "https://pay.weixin.qq.com/wiki/doc/api/jsapi.php?chapter=9_6">下载对账单</a>
	 * </pre>
	 *
	 * @param billDate
	 *            对账单日期 bill_date 下载对账单的日期，格式：20140603
	 * @param billType
	 *            账单类型 bill_type
	 *            ALL，返回当日所有订单信息，默认值，SUCCESS，返回当日成功支付的订单，REFUND，返回当日退款订单
	 * @param tarType
	 *            压缩账单 tar_type 非必传参数，固定值：GZIP，返回格式为.gzip的压缩包账单。不传则默认为数据流形式。
	 * @param deviceInfo
	 *            设备号 device_info 非必传参数，终端设备号
	 * @return 保存到本地的临时文件
	 * @throws Exception 异常
	 */
	@GetMapping("/downloadBill")
	public WxPayBillResult downloadBill(@RequestParam String billDate, @RequestParam String billType,
										@RequestParam String tarType, @RequestParam String deviceInfo, HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).downloadBill(billDate, billType, tarType, deviceInfo);
	}

	/**
	 * <pre>
	 * 提交刷卡支付
	 * 文档地址：https://pay.weixin.qq.com/wiki/doc/api/micropay.php?chapter=9_10&index=1
	 * 应用场景：
	 * 收银员使用扫码设备读取微信用户刷卡授权码以后，二维码或条码信息传送至商户收银台，由商户收银台或者商户后台调用该接口发起支付。
	 * 提醒1：提交支付请求后微信会同步返回支付结果。当返回结果为“系统错误”时，商户系统等待5秒后调用【查询订单API】，查询支付实际交易结果；当返回结果为“USERPAYING”时，商户系统可设置间隔时间(建议10秒)重新查询支付结果，直到支付成功或超时(建议30秒)；
	 * 提醒2：在调用查询接口返回后，如果交易状况不明晰，请调用【撤销订单API】，此时如果交易失败则关闭订单，该单不能再支付成功；如果交易成功，则将扣款退回到用户账户。当撤销无返回或错误时，请再次调用。注意：请勿扣款后立即调用【撤销订单API】,建议至少15秒后再调用。撤销订单API需要双向证书。
	 * 接口地址：   https://api.mch.weixin.qq.com/pay/micropay
	 * 是否需要证书：不需要。
	 * </pre>
	 * @throws Exception 异常
	 */
	@PostMapping("/micropay")
	public WxPayMicropayResult micropay(@RequestBody WxPayMicropayRequest request, HttpServletRequest req) throws Exception {
		return payInfoService.findWxPayService(req).micropay(request);
	}

	/**
	 * <pre>
	 * 撤销订单API
	 * 文档地址：https://pay.weixin.qq.com/wiki/doc/api/micropay.php?chapter=9_11&index=3
	 * 应用场景：
	 *  支付交易返回失败或支付系统超时，调用该接口撤销交易。如果此订单用户支付失败，微信支付系统会将此订单关闭；如果用户支付成功，微信支付系统会将此订单资金退还给用户。
	 *  注意：7天以内的交易单可调用撤销，其他正常支付的单如需实现相同功能请调用申请退款API。提交支付交易后调用【查询订单API】，没有明确的支付结果再调用【撤销订单API】。
	 *  调用支付接口后请勿立即调用撤销订单API，建议支付后至少15s后再调用撤销订单接口。
	 *  接口链接 ：https://api.mch.weixin.qq.com/secapi/pay/reverse
	 *  是否需要证书：请求需要双向证书。
	 * </pre>
	 * @throws Exception 异常
	 */
	@PostMapping("/reverseOrder")
	public WxPayOrderReverseResult reverseOrder(@RequestBody WxPayOrderReverseRequest request, HttpServletRequest req) throws Exception {
		return payInfoService.findWxPayService(req).reverseOrder(request);
	}

	@GetMapping("/getSandboxSignKey")
	public String getSandboxSignKey(HttpServletRequest request) throws Exception {
		return payInfoService.findWxPayService(request).getSandboxSignKey();
	}

	@PostMapping("/sendCoupon")
	public WxPayCouponSendResult sendCoupon(@RequestBody WxPayCouponSendRequest request, HttpServletRequest req) throws Exception {
		return payInfoService.findWxPayService(req).sendCoupon(request);
	}

	@PostMapping("/queryCouponStock")
	public WxPayCouponStockQueryResult queryCouponStock(@RequestBody WxPayCouponStockQueryRequest request, HttpServletRequest req)
			throws Exception {
		return payInfoService.findWxPayService(req).queryCouponStock(request);
	}

	@PostMapping("/queryCouponInfo")
	public WxPayCouponInfoQueryResult queryCouponInfo(@RequestBody WxPayCouponInfoQueryRequest request, HttpServletRequest req)
			throws Exception {
		return payInfoService.findWxPayService(req).queryCouponInfo(request);
	}

	@PostMapping("/queryComment")
	public String queryComment(Date beginDate, Date endDate, Integer offset, Integer limit) {
		return this.queryComment(beginDate, endDate, offset, limit);
	}

	/**
	 * 获取支付平台信息
	 * @param id 支付ID
	 * @return 支付信息
	 */
	@RequestMapping(value = "/info/get", method = RequestMethod.GET)
	public Object findPayInfoById(@RequestParam(name = "id") Long id) throws Exception {
		PayInfoEntity info = payInfoService.get(id);
		if(info == null) {
			throw new EsRuntimeException(WxErrorConstants.DATA_NOT_FOUND);
		}
		return JsonResult.success(info);
	}

	/**
	 * 创建支付平台
	 * @param info 支付平台信息
	 * @return 支付平台信息
	 */
	@RequestMapping(value = "/info/create", method = RequestMethod.POST)
	public Object createPayInfo(@RequestBody PayInfoEntity info) {
		payInfoService.create(info);
		return JsonResult.success(info);
	}

	/**
	 * 更新支付平台信息
	 * @param info 支付平台信息
	 * @return 修改后支付平台信息
	 */
	@RequestMapping(value = "/info/update", method = RequestMethod.POST)
	public Object updatePayInfo(@RequestBody PayInfoEntity info) {
		payInfoService.update(info);
		return JsonResult.success(info);
	}

	/**
	 * 删除支付平台
	 * @param id 支付平台ID
	 * @return 删除结果
	 */
	@RequestMapping(value = "/info/delete", method = RequestMethod.GET)
	public Object deletePayInfo(@RequestParam(name = "id") Long id) {
		payInfoService.delete(id);
		return JsonResult.success();
	}

	/**
	 * 支付平台列表
	 * @param page 分页信息
	 * @return 支付平台列表
	 */
	@RequestMapping(value = "/info/list", method = RequestMethod.POST)
	public Object listPayInfo(@RequestBody JsonRequestPage<PayInfoEntity> page) {
		PayInfoEntity example = Optional.ofNullable(page.getSearch()).orElse(new PayInfoEntity());
		example.setClientId(EsContextHolder.getContext().getClientId());
		PageInfo<PayInfoEntity> list = payInfoService.findPage(example, page.getPageSize(), page.getPageNum(), page.getOrderBy());
		JsonResultPage<PayInfoEntity> result = new JsonResultPage<>(list.getList());
		result.setPageNum(list.getPageNum());
		result.setTotal(list.getTotal());
		return result;
	}
}