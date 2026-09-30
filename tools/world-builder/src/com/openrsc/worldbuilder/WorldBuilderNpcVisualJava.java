package com.openrsc.worldbuilder;

import java.util.*;
import java.util.regex.*;

/** Bounded lexical and constant-expression subset; never compiles or executes target Java. */
final class WorldBuilderNpcVisualJava {
	static List<String> tokens(String source) {
		if (source.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("Source exceeds 4 MiB");
		List<String> result = new ArrayList<>();
		for (int at=0;at<source.length();) {
			char c=source.charAt(at);if(Character.isWhitespace(c)){at++;continue;}
			if(source.startsWith("//",at)){int next=source.indexOf('\n',at+2);at=next<0?source.length():next+1;continue;}
			if(source.startsWith("/*",at)){int next=source.indexOf("*/",at+2);if(next<0)throw new IllegalArgumentException("Unclosed source comment");at=next+2;continue;}
			int start=at++;
			if(source.startsWith("\"\"\"",start)) {
				int close=start+3;
				while(true){close=source.indexOf("\"\"\"",close);if(close<0)throw new IllegalArgumentException("Unclosed text block");int slashes=0;for(int p=close-1;p>=start&&source.charAt(p)=='\\';p--)slashes++;if(slashes%2==0)break;close+=3;}
				at=close+3;
			} else if(c=='"'||c=='\'') {
				boolean closed=false;
				while(at<source.length()) {char next=source.charAt(at++);if(next=='\\'){if(at>=source.length())break;at++;}else if(next==c){closed=true;break;}else if(next=='\n'||next=='\r')throw new IllegalArgumentException("Multiline source literal is unsupported");}
				if(!closed)throw new IllegalArgumentException("Unclosed source literal");
			} else if(Character.isJavaIdentifierStart(c)) {
				while(at<source.length()&&Character.isJavaIdentifierPart(source.charAt(at)))at++;
			} else if(Character.isDigit(c)) {
				while(at<source.length()&&Character.isLetterOrDigit(source.charAt(at)))at++;
			} else {
				for(String operator:Arrays.asList(">>>","...",">>","==","!=","<=",">=","&&","||","++","+=","--","-=","<<"))if(source.startsWith(operator,start)){at=start+operator.length();break;}
			}
			result.add(source.substring(start,at));
			if(result.size()>500000)throw new IllegalArgumentException("Source token budget exceeded");
		}
		return result;
	}
	static int end(List<String> ts, int start) {
		String open = ts.get(start), close = "(".equals(open) ? ")" : "{".equals(open) ? "}" : "]";
		int level = 0;
		for (int i = start; i < ts.size(); i++) {
			if (open.equals(ts.get(i))) level++;
			if (close.equals(ts.get(i)) && --level == 0) return i;
		}
		throw new IllegalArgumentException("Unbalanced source delimiters");
	}
	static String text(List<String> ts) { return String.join(" ", ts); }
	static boolean identifier(String s) { return s.matches("[A-Za-z_$][A-Za-z0-9_$]*"); }
	static String string(String s) {
		if (!s.matches("\"[^\"\\\\\\r\\n]*\"")) throw new IllegalArgumentException("Only literal unescaped source paths/keys are supported");
		return s.substring(1, s.length() - 1);
	}
	static Map<String,String> match(List<String> actual, String template) {
		List<String> expected = tokens(template);
		if (expected.size() != actual.size()) return null;
		Map<String,String> vars = new LinkedHashMap<>();
		for (int i = 0; i < expected.size(); i++) {
			String t = expected.get(i), a = actual.get(i);
			if (t.startsWith("$")) {
				if (!identifier(a) && !a.startsWith("\"") && !a.matches("[0-9]+")) return null;
				String before = vars.putIfAbsent(t.substring(1), a);
				if (before != null && !before.equals(a)) return null;
			} else if (!t.equals(a)) return null;
		}
		return vars;
	}
	static final class Method {
		final String name; final List<String> params, body;
		Method(String n, List<String> p, List<String> b) { name=n; params=p; body=b; }
	}
	static List<Method> methods(List<String> ts) {
		List<Method> result = new ArrayList<>();
		for (int i = 1; i + 1 < ts.size(); i++) if ("(".equals(ts.get(i)) && identifier(ts.get(i-1))) {
			int close = end(ts, i), body = close + 1;
			if (body < ts.size() && "throws".equals(ts.get(body))) { body++; while (body < ts.size() && (identifier(ts.get(body)) || ts.get(body).equals(",") || ts.get(body).equals("."))) body++; }
			if (body < ts.size() && "{".equals(ts.get(body)) && !Arrays.asList("if","for","while","switch","catch","synchronized").contains(ts.get(i-1))) {
				int bodyEnd = end(ts, body);
				result.add(new Method(ts.get(i-1), new ArrayList<>(ts.subList(i+1,close)), new ArrayList<>(ts.subList(body+1,bodyEnd))));
			}
		}
		return result;
	}
	static Method method(List<Method> methods, String name) {
		Method result = null;
		for (Method m : methods) if (m.name.equals(name)) {
			if (result != null) throw new IllegalArgumentException("Ambiguous source method " + name);
			result = m;
		}
		if (result == null) throw new IllegalArgumentException("Missing source method " + name);
		return result;
	}
	/** Only literals, enum identity, columns[n], pure no-arg returns and integer operations. */
	static Object evaluate(List<String> ts, String identity, Map<String,Object> fields,
		List<Method> methods, int depth) {
		if (depth > 12 || ts.size() > 256) throw new IllegalArgumentException("Constant expression budget exceeded");
		return new Expr(ts, identity, fields, methods, depth).all();
	}
	private static final class Expr {
		final List<String> t; final String id; final Map<String,Object> fields; final List<Method> methods; final int depth; int at;
		Expr(List<String> t,String id,Map<String,Object> f,List<Method> m,int d) {this.t=t;this.id=id;fields=f;methods=m;depth=d;}
		boolean take(String s) {if(at<t.size()&&s.equals(t.get(at))){at++;return true;}return false;}
		void require(String s) {if(!take(s))throw new IllegalArgumentException("Unsupported constant expression");}
		Object all(){Object v=conditional();if(at!=t.size())throw new IllegalArgumentException("Unsupported constant expression suffix");return v;}
		Object conditional(){Object v=or();if(take("?")){Object yes=conditional();require(":");Object no=conditional();return bool(v)?yes:no;}return v;}
		Object or(){Object v=and();while(take("||")){Object b=and();v=bool(v)|bool(b);}return v;}
		Object and(){Object v=eq();while(take("&&")){Object b=eq();v=bool(v)&bool(b);}return v;}
		Object eq(){Object v=sum();while(at<t.size()&&(t.get(at).equals("==")||t.get(at).equals("!="))){boolean same=take("==");if(!same)require("!=");Object b=sum();v=same==Objects.equals(v,b);}return v;}
		Object sum(){Object v=product();while(at<t.size()&&(t.get(at).equals("+")||t.get(at).equals("-"))){boolean plus=take("+");if(!plus)require("-");Object b=product();v=plus?Math.addExact(number(v),number(b)):Math.subtractExact(number(v),number(b));}return v;}
		Object product(){Object v=atom();while(at<t.size()&&(t.get(at).equals("*")||t.get(at).equals("/"))){boolean mult=take("*");if(!mult)require("/");Object b=atom();v=mult?Math.multiplyExact(number(v),number(b)):number(v)/number(b);}return v;}
		Object atom(){
			if(take("(")){Object v=conditional();require(")");return v;}if(take("-"))return -number(atom());if(take("!"))return !bool(atom());
			if(at==t.size())throw new IllegalArgumentException("Truncated expression");String name=t.get(at++);
			if(name.matches("[0-9]+"))return Long.valueOf(name);if(name.equals("true")||name.equals("false"))return Boolean.valueOf(name);
			if(name.equals("this")){if(!take("."))return id;name=t.get(at++);}
			if(take("(")){require(")");Method method=method(methods,name);if(!method.params.isEmpty()||method.body.size()<3||!method.body.get(0).equals("return")||!method.body.get(method.body.size()-1).equals(";"))throw new IllegalArgumentException("Nonconstant source method "+name);return evaluate(method.body.subList(1,method.body.size()-1),id,fields,methods,depth+1);}
			if (!fields.containsKey(name)) throw new IllegalArgumentException("Unknown constant identifier " + name);
			Object value=fields.get(name);
			if(take("[")){int index=Math.toIntExact(number(conditional()));require("]");return ((List<?>)value).get(index);}return value;
		}
	}
	static long number(Object n){if(!(n instanceof Long))throw new IllegalArgumentException("Expected integer expression");return (Long)n;}
	static boolean bool(Object n){if(!(n instanceof Boolean))throw new IllegalArgumentException("Expected boolean expression");return (Boolean)n;}
}
