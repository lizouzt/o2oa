package com.x.base.core.project.http;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.time.DateUtils;

import com.x.base.core.project.config.Config;
import com.x.base.core.project.logger.Logger;
import com.x.base.core.project.logger.LoggerFactory;
import com.x.base.core.project.tools.Crypto;
import com.x.base.core.project.tools.DateTools;
import com.x.base.core.project.tools.DomainTools;
import com.x.base.core.project.tools.URLTools;

public class HttpToken {

	private static Logger logger = LoggerFactory.getLogger(HttpToken.class);

	// public static final String X_Token = "x-token";
	public static final String X_Authorization = "authorization";
	public static final String X_Person = "x-person";
	public static final String X_DISTINGUISHEDNAME = "x-distinguishedName";
	public static final String X_Client = "x-client";
	public static final String X_Debugger = "x-debugger";
	public static final String COOKIE_ANONYMOUS_VALUE = "anonymous";
	public static final String SET_COOKIE = "Set-Cookie";

	private static final String RegularExpression_IP = "([1-9]|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])(\\.(\\d|[1-9]\\d|1\\d{2}|2[0-4]\\d|25[0-5])){3}";
	private static final String RegularExpression_Token = "^(anonymous|user|manager|cipher)([2][0][1-9][0-9][0-1][0-9][0-3][0-9][0-5][0-9][0-5][0-9][0-5][0-9])(\\S{1,})$";

	public EffectivePerson who(HttpServletRequest request, HttpServletResponse response, String key) throws Exception {
		EffectivePerson effectivePerson = this.who(this.getToken(request), key, remoteAddress(request));
		effectivePerson.setRemoteAddress(HttpToken.remoteAddress(request));
		effectivePerson.setUserAgent(this.userAgent(request));
		effectivePerson.setUri(request.getRequestURI());
		// 加入调试标记
		Object debugger = request.getHeader(HttpToken.X_Debugger);
		effectivePerson.setDebugger((null != debugger) && BooleanUtils.toBoolean(Objects.toString(debugger)));
		setAttribute(request, effectivePerson);
		setToken(request, response, effectivePerson);
		return effectivePerson;
	}

	public EffectivePerson who(String token, String key, String address) {
		if (StringUtils.length(token) < 16) {
			/* token应该是8的倍数有可能前台会输入null空值等可以通过这个过滤掉 */
			return EffectivePerson.anonymous();
		}
		try {
			String plain = "";
			try {
				plain = Crypto.decrypt(token, key);
			} catch (Exception e) {
				logger.warn("can not decrypt token:{}, {}, remote address:{}.", token, e.getMessage(), address);
				return EffectivePerson.anonymous();
			}
			Pattern pattern = Pattern.compile(RegularExpression_Token, Pattern.CASE_INSENSITIVE);
			Matcher matcher = pattern.matcher(plain);
			if (!matcher.find()) {
				// 不报错,跳过错误,将用户设置为anonymous
				logger.warn("token format error:{}, remote address:{}.", plain, address);
				return EffectivePerson.anonymous();
			}
			Date date = DateUtils.parseDate(matcher.group(2), DateTools.formatCompact_yyyyMMddHHmmss);
			TokenType tokenType = TokenType.valueOf(matcher.group(1));
			long diff = (System.currentTimeMillis() - date.getTime());
			diff = Math.abs(diff);
			if (TokenType.user.equals(tokenType) || TokenType.manager.equals(tokenType)) {
				if (diff > (60000L * Config.person().getTokenExpiredMinutes())) {
					// 不报错,跳过错误,将用户设置为anonymous
					logger.warn("token expired, user:{}, token:{}, remote address:{}.",
							URLDecoder.decode(matcher.group(3), StandardCharsets.UTF_8.name()), plain, address);
					return EffectivePerson.anonymous();
				}
			}
			if (TokenType.cipher.equals(tokenType) && (diff > (60000 * 20))) {
				// 不报错,跳过错误,将用户设置为anonymous
				return EffectivePerson.anonymous();
			}
			return new EffectivePerson(URLDecoder.decode(matcher.group(3), StandardCharsets.UTF_8.name()), tokenType,
					key);
		} catch (Exception e) {
			e.printStackTrace();
		}
		return EffectivePerson.anonymous();
	}

	public void deleteToken(HttpServletRequest request, HttpServletResponse response) throws Exception {
		try {
			// String cookie = X_Token + "=; path=/; domain=" +
			// this.domain(request) + "; max-age=0
			String cookie = Config.person().getTokenName() + "=" + COOKIE_ANONYMOUS_VALUE + "; path=/; domain="
					+ this.domain(request)
					+ (BooleanUtils.isTrue(Config.person().getTokenCookieHttpOnly()) ? "; HttpOnly" : "");
			response.setHeader(SET_COOKIE, cookie);
		} catch (Exception e) {
			throw new Exception("delete Token cookie error.", e);
		}
	}

	public void setToken(HttpServletRequest request, HttpServletResponse response, EffectivePerson effectivePerson)
			throws Exception {
		switch (effectivePerson.getTokenType()) {
		case anonymous:
			break;
		case user:
			this.setResponseToken(request, response, effectivePerson);
			break;
		case manager:
			this.setResponseToken(request, response, effectivePerson);
			break;
		case cipher:
			this.deleteToken(request, response);
			break;
		default:
			break;
		}
	}

	private void setResponseToken(HttpServletRequest request, HttpServletResponse response,
			EffectivePerson effectivePerson) throws Exception {
		if (!StringUtils.isEmpty(effectivePerson.getToken())) {
			String cookie = Config.person().getTokenName() + "=" + effectivePerson.getToken() + "; path=/; domain="
					+ this.domain(request)
					+ (BooleanUtils.isTrue(Config.person().getTokenCookieHttpOnly()) ? "; HttpOnly" : "");
			response.setHeader(SET_COOKIE, cookie);
			response.setHeader(Config.person().getTokenName(), effectivePerson.getToken());
		}
	}

	public void setResponseToken(HttpServletRequest request, HttpServletResponse response, String tokenName,
			String token) throws Exception {
		if (!StringUtils.isEmpty(token)) {
			String cookie = tokenName + "=" + token + "; path=/; domain=" + this.domain(request)
					+ (BooleanUtils.isTrue(Config.person().getTokenCookieHttpOnly()) ? "; HttpOnly" : "");
			response.setHeader(SET_COOKIE, cookie);
			response.setHeader(tokenName, token);
		}
	}

	public String getToken(HttpServletRequest request) throws Exception {
		String token = null;
		token = URLTools.getQueryStringParameter(request.getQueryString(), Config.person().getTokenName());
		if (StringUtils.isEmpty(token)) {
			if (null != request.getCookies()) {
				for (Cookie c : request.getCookies()) {
					if (StringUtils.equals(Config.person().getTokenName(), c.getName())) {
						token = c.getValue();
						break;
					}
				}
			}
		}
		if (StringUtils.isEmpty(token)) {
			token = request.getHeader(Config.person().getTokenName());
		}
		if (StringUtils.isEmpty(token)) {
			// 如果使用oauth bearer 通过此传递认证信息.需要进行判断,格式为 Bearer xxxxxxx
			String value = request.getHeader(X_Authorization);
			if (!StringUtils.contains(value, " ")) {
				token = value;
			}
		}
		// 此代码将导致input被关闭.
		// if (StringUtils.isEmpty(token)) {
		// token = request.getParameter(X_Token);
		// }
		return token;
	}

	private String domain(HttpServletRequest request) throws Exception {
		String str = request.getServerName();
		if (StringUtils.contains(str, ".")) {
			Pattern pattern = Pattern.compile(RegularExpression_IP);
			Matcher matcher = pattern.matcher(str);
			if (!matcher.find()) {
				if (StringUtils.equalsIgnoreCase(DomainTools.getMainDomain(str), str)) {
					return str;
				} else {
					return "." + StringUtils.substringAfter(str, ".");
				}
			}
		}
		return str;
	}

	private void setAttribute(HttpServletRequest request, EffectivePerson effectivePerson) {
		request.setAttribute(X_Person, effectivePerson);
		request.setAttribute(X_DISTINGUISHEDNAME, effectivePerson.getDistinguishedName());
	}

	public static String remoteAddress(HttpServletRequest request) {
		String value = Objects.toString(request.getHeader("X-Forwarded-For"), "");
		if (StringUtils.isEmpty(value)) {
			value = Objects.toString(request.getRemoteAddr(), "");
		}
		return value;
	}

	private String userAgent(HttpServletRequest request) {
		return Objects.toString(request.getHeader("User-Agent"), "");
	}

}