package cn.jia.core.util;

import java.util.ArrayList;
import java.util.List;

public class PageUtil {

	/**
	 * 返回每页的起始行和结束行
	 */
	public static int[] getRows(int page, int pageSize, int max) {
		if (page <= 1) {
			page = 1;
		}

		int a[] = new int[2];
		try {
			a[0] = (page - 1) * pageSize;
			if (page * pageSize >= max) {
				a[1] = max;
			} else {
				a[1] = page * pageSize;
			}
		} catch (RuntimeException e) {
			e.printStackTrace();
			a[0] = 0;
			a[1] = pageSize - 1;
		}
		return a;
	}

	/**
	 * 
	 * @param page
	 *            页数,第几页
	 * @param max
	 *            总记录
	 * @return
	 */
	public static List<Integer> getpageList(int page, int pageSize, int max) {
		int maxPage = 0;
		if (max % pageSize == 0) {
			maxPage = ((int) (max / pageSize));
		} else {
			maxPage = ((int) (max / pageSize)) + 1;
		}
		List<Integer> l = new ArrayList<Integer>();
		int longs = 5;

		if (page <= longs / 2) {
			for (int i = 0; i < longs; i++) {
				l.add(1 + i);
			}
		} else if (page >= maxPage - longs / 2) {
			l.add(1);
			l.add(maxPage - 4);
			l.add(maxPage - 3);
			l.add(maxPage - 2);
			l.add(maxPage - 1);
			l.add(maxPage);
		} else {
			l.add(1);
			l.add(page - 2);
			l.add(page - 1);
			l.add(page);
			l.add(page + 1);
			l.add(page + 2);
			l.add(maxPage);
		}

		return l;
	}
}