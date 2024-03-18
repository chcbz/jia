package cn.jia.mat.api;

import cn.jia.core.entity.JsonResult;
import cn.jia.core.exception.EsRuntimeException;
import cn.jia.mat.common.MatErrorConstants;
import cn.jia.mat.entity.MatPhraseEntity;
import cn.jia.mat.entity.MatPhraseVoteEntity;
import cn.jia.mat.service.MatPhraseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/phrase")
@Slf4j
public class PhraseController {
	
	@Autowired
	private MatPhraseService phraseService;
	
	/**
	 * 获取短语信息
	 * @param id 短语ID
	 * @return 短语信息
	 */
	@GetMapping(value = "/get")
	public Object findById(@RequestParam(name = "id") Long id) throws Exception{
		MatPhraseEntity phrase = phraseService.get(id);
		if(phrase == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
		return JsonResult.success(phrase);
	}
	
	/**
	 * 创建短语
	 * @param phrase 短语信息
	 * @return 短语信息
	 */
	@PostMapping(value = "/create")
	public Object create(@RequestBody MatPhraseEntity phrase) throws Exception {
		phraseService.create(phrase);
		return JsonResult.success();
	}

	/**
	 * 更新短语信息
	 * @param phrase 短语信息
	 * @return 短语信息
	 */
	@PostMapping(value = "/update")
	public Object update(@RequestBody MatPhraseEntity phrase) {
		phraseService.update(phrase);
		return JsonResult.success();
	}

	/**
	 * 删除短语
	 * @param id 短语ID
	 * @return 处理结果
	 */
	@GetMapping(value = "/delete")
	public Object delete(@RequestParam(name = "id") Long id) throws Exception {
		MatPhraseEntity phrase = phraseService.get(id);
		if(phrase == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
		phraseService.delete(id);
		return JsonResult.success();
	}
	
	/**
	 * 获取所有短语信息
	 * @return 短语列表
	 */
	@PostMapping(value = "/get/random")
	public Object getRandom(@RequestBody MatPhraseEntity example) throws Exception {
		MatPhraseEntity phrase = phraseService.findRandom(example);
		if(phrase == null) {
			throw new EsRuntimeException(MatErrorConstants.MEDIA_NOT_EXIST);
		}
		return JsonResult.success(phrase);
	}

	/**
	 * 投票
	 * @param vote 投票信息
	 * @return 处理结果
	 * @throws Exception 异常信息
	 */
	@PostMapping(value = "/vote")
	public Object vote(@RequestBody MatPhraseVoteEntity vote) throws Exception {
		phraseService.vote(vote);
		return JsonResult.success();
	}

	/**
	 * 访问
	 * @param id 短语ID
	 * @return 处理结果
	 * @throws Exception 异常信息
	 */
	@GetMapping(value = "/read")
	public Object read(@RequestParam Long id) throws Exception {
		phraseService.read(id);
		return JsonResult.success();
	}
}
