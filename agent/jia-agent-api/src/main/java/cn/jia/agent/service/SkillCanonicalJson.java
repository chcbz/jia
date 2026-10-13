package cn.jia.agent.service;

import java.math.*;
import java.util.*;

/** Portable canonical JSON shared by skill snapshots, results and materials.
 * UTF16 lexical object keys, preserved arrays, ECMAScript shortest IEEE754 decimals.
 * Unsafe integers/nonfinite/non-JSON values are rejected; no credentials or paths added. */
public final class SkillCanonicalJson {
    private SkillCanonicalJson() { }
    public static String write(Object value) {var out=new StringBuilder();append(out,value);return out.toString();}
    private static void integer(StringBuilder out,BigInteger n) {if(n.abs().compareTo(BigInteger.valueOf(9007199254740991L))>0)throw new IllegalArgumentException("Use strings for unsafe integers");out.append(n);}
    private static BigDecimal shortest(double value) {
        if(value==0)return BigDecimal.ZERO;var exact=new BigDecimal(value);
        for(int p=1;p<=17;p++){var rounded=exact.round(new MathContext(p,RoundingMode.HALF_EVEN));if(Double.doubleToLongBits(rounded.doubleValue())==Double.doubleToLongBits(value))return rounded.stripTrailingZeros();}
        throw new IllegalArgumentException("Unrepresentable JSON number");
    }
    private static void quote(StringBuilder out,String value) {
        out.append('"');for(int i=0;i<value.length();i++){char c=value.charAt(i);switch(c){
            case '"'->out.append("\\\"");case '\\'->out.append("\\\\");case '\b'->out.append("\\b");case '\f'->out.append("\\f");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");
            default->{if(c<32 || Character.isSurrogate(c)&&!(Character.isHighSurrogate(c)&&i+1<value.length()&&Character.isLowSurrogate(value.charAt(i+1)))&&!(Character.isLowSurrogate(c)&&i>0&&Character.isHighSurrogate(value.charAt(i-1)))) {out.append("\\u");for(int shift=12;shift>=0;shift-=4)out.append("0123456789abcdef".charAt((c>>shift)&15));}else out.append(c);}
        }}out.append('"');
    }
    private static void append(StringBuilder out,Object value) {
        if(value==null)out.append("null");
        else if(value instanceof String text)quote(out,text);
        else if(value instanceof Boolean bool)out.append(bool);
        else if(value instanceof Byte||value instanceof Short||value instanceof Integer||value instanceof Long||value instanceof BigInteger)integer(out,new BigInteger(value.toString()));
        else if(value instanceof BigDecimal d&&d.scale()<=0)integer(out,d.toBigIntegerExact());
        else if(value instanceof Number n){double d=n.doubleValue();if(!Double.isFinite(d)||d==Math.rint(d)&&Math.abs(d)>9007199254740991d)throw new IllegalArgumentException("Nonportable JSON number");var decimal=shortest(d);if(d==0)out.append('0');else if(Math.abs(d)>=1e-6&&Math.abs(d)<1e21)out.append(decimal.toPlainString());else out.append(decimal.toString().replace("E","e"));}
        else if(value instanceof Map<?,?> map){var sorted=new TreeMap<String,Object>();for(var e:map.entrySet()){if(!(e.getKey() instanceof String key))throw new IllegalArgumentException("JSON keys must be strings");sorted.put(key,e.getValue());}out.append('{');boolean first=true;for(var e:sorted.entrySet()){if(!first)out.append(',');quote(out,e.getKey());out.append(':');append(out,e.getValue());first=false;}out.append('}');}
        else if(value instanceof Iterable<?> list){out.append('[');boolean first=true;for(Object item:list){if(!first)out.append(',');append(out,item);first=false;}out.append(']');}
        else throw new IllegalArgumentException("Not a JSON value");
    }
}
