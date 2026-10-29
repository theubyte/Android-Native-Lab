package de.millione.schulranzen;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pure-Java calculator/parser plus handwriting-result cleanup. */
public final class MathEngine {
    public static final class Result {
        public final String expression;
        public final double value;
        Result(String e,double v){expression=e;value=v;}
    }

    private MathEngine(){}

    public static Result bestCandidate(List<String> candidates){
        if(candidates==null)return null;
        Result best=null;int bestScore=Integer.MIN_VALUE;
        for(String raw:candidates){
            for(String candidate:variants(raw)){
                String expr=normalize(candidate,true);
                if(expr==null||expr.isEmpty())continue;
                int eq=expr.indexOf('=');if(eq>=0)expr=expr.substring(0,eq);
                if(expr.isEmpty()||countDigits(expr)==0||!looksMathy(expr))continue;
                try{
                    double v=evaluate(expr);if(!Double.isFinite(v))continue;
                    int score=score(raw,expr);
                    if(score>bestScore){bestScore=score;best=new Result(expr,v);}
                }catch(Exception ignored){}
            }
        }
        return best;
    }

    public static double evaluate(String raw){return new Parser(normalize(raw,false)).parse();}

    public static String normalize(String raw,boolean handwriting){
        if(raw==null)return null;
        String s=raw.trim().toLowerCase(Locale.ROOT)
                .replace('−','-').replace('–','-').replace('—','-')
                .replace('×','*').replace('·','*').replace('∙','*').replace('•','*')
                .replace('÷','/').replace(':','/').replace('∶','/').replace('：','/')
                .replace('＝','=').replace('≈','=').replace('≃','=').replace('≡','=')
                .replace('✕','*').replace('✖','*')
                .replace("√","sqrt").replace("π","pi").replace("²","^2").replace("³","^3")
                .replace('[','(').replace(']',')').replace('{','(').replace('}',')')
                .replace('（','(').replace('）',')').replace(',','.').replaceAll("\\s+","");
        // Handwriting models often return x/X for multiplication.
        s=s.replaceAll("(?<=[0-9)])x(?=[0-9(])","*");
        s=s.replaceAll("(?<=[0-9)])x$","*");
        s=s.replaceAll("^x(?=[0-9(])","*");
        // Colon is sometimes recognized as a semicolon in handwritten arithmetic.
        s=s.replaceAll("(?<=[0-9)])\\;(?=[0-9(])","/");
        if(handwriting){
            // Conservative OCR-style corrections only where a digit is expected.
            s=s.replaceAll("(?<=[0-9(+\\-*/^])o(?=[0-9)+\\-*/^])","0");
            s=s.replaceAll("^o(?=[0-9.+\\-*/^])","0");
            s=s.replaceAll("(?<=[0-9(+\\-*/^])l(?=[0-9)+\\-*/^])","1");
            s=s.replaceAll("^l(?=[0-9.+\\-*/^])","1");
            s=s.replaceAll("(?<=[0-9])s(?=[0-9])","5");
        }
        while(s.endsWith("="))s=s.substring(0,s.length()-1);
        if(s.length()>160)return null;
        // During live handwriting a closing parenthesis is often the last stroke.
        int open=0;for(int i=0;i<s.length();i++){char c=s.charAt(i);if(c=='(')open++;else if(c==')')open--;if(open<0)return null;}
        if(handwriting&&open>0&&open<=3){StringBuilder b=new StringBuilder(s);while(open-->0)b.append(')');s=b.toString();}
        if(!s.matches("[0-9+\\-*/^%().=a-z!]+"))return null;
        String unknown=s.replaceAll("sqrt|sin|cos|tan|ln|log|abs|pi|e"," ").replaceAll("[^a-z]","").trim();
        if(!unknown.isEmpty())return null;
        return s;
    }

    private static ArrayList<String> variants(String raw){
        ArrayList<String> out=new ArrayList<>();if(raw==null)return out;addUnique(out,raw);
        String a=raw.replace('：',':').replace('；',';').replace('／','/').replace('＊','*').replace('＝','=').replace('✕','×').replace('✖','×');addUnique(out,a);
        // Recognition sometimes returns several visual lines. Keep the complete candidate first,
        // then also try each meaningful line instead of accidentally concatenating two tasks.
        for(String line:a.split("[\r\n]+")){String q=line.trim();if(!q.isEmpty())addUnique(out,q);}
        String b=a.replace("=","");addUnique(out,b);
        // Numeric handwriting fallbacks. Only create them when no named calculator function is present.
        String low=a.toLowerCase(Locale.ROOT);
        if(!(low.contains("sin")||low.contains("cos")||low.contains("tan")||low.contains("sqrt")||low.contains("log")||low.contains("ln")||low.contains("abs"))){
            String common=low.replace('o','0').replace('l','1').replace('i','1').replace('z','2').replace('s','5').replace('g','9');
            addUnique(out,common);
            addUnique(out,common.replace('b','6'));
            addUnique(out,common.replace('b','8'));
        }
        return out;
    }
    private static void addUnique(ArrayList<String> out,String s){if(s!=null&&!s.isEmpty()&&!out.contains(s))out.add(s);}

    private static int countDigits(String s){int n=0;for(int i=0;i<s.length();i++)if(Character.isDigit(s.charAt(i)))n++;return n;}
    private static boolean looksMathy(String s){return s.matches(".*[+\\-*/^%()].*")||s.contains("sqrt")||s.contains("sin")||s.contains("cos")||s.contains("tan")||s.contains("ln")||s.contains("log")||s.contains("abs");}
    private static int score(String raw,String normalized){int sc=countDigits(normalized)*4;for(char c:normalized.toCharArray())if("+-*/^%()".indexOf(c)>=0)sc+=3;if(raw!=null&&raw.contains("="))sc+=2;sc-=Math.max(0,normalized.length()-45);return sc;}

    private static final class Parser {
        final String s;int p=0;
        Parser(String s){if(s==null)throw new IllegalArgumentException();this.s=s;}
        double parse(){if(s.isEmpty())throw new IllegalArgumentException();double v=expr();if(p!=s.length())throw new IllegalArgumentException();return v;}
        double expr(){double v=term();while(p<s.length()){if(eat('+'))v+=term();else if(eat('-'))v-=term();else break;}return v;}
        double term(){double v=power();while(p<s.length()){if(eat('*'))v*=power();else if(eat('/')){double d=power();if(Math.abs(d)<1e-15)throw new ArithmeticException();v/=d;}else if(startsPrimary())v*=power();else break;}return v;}
        double power(){double v=unary();if(eat('^'))v=Math.pow(v,power());return v;}
        double unary(){if(eat('+'))return unary();if(eat('-'))return -unary();return postfix();}
        double postfix(){double v=primary();while(p<s.length()){if(eat('%'))v/=100d;else if(eat('!')){if(v<0||v>170||Math.rint(v)!=v)throw new IllegalArgumentException();double f=1;for(int i=2;i<=(int)v;i++)f*=i;v=f;}else break;}return v;}
        double primary(){
            if(match("pi"))return Math.PI;if(match("e"))return Math.E;
            String fn=null;String[] fs={"sqrt","sin","cos","tan","ln","log","abs"};for(String f:fs)if(s.startsWith(f,p)){fn=f;p+=f.length();break;}
            if(fn!=null){expect('(');double v=expr();expect(')');switch(fn){case "sqrt":if(v<0)throw new IllegalArgumentException();return Math.sqrt(v);case "sin":return Math.sin(Math.toRadians(v));case "cos":return Math.cos(Math.toRadians(v));case "tan":return Math.tan(Math.toRadians(v));case "ln":if(v<=0)throw new IllegalArgumentException();return Math.log(v);case "log":if(v<=0)throw new IllegalArgumentException();return Math.log10(v);default:return Math.abs(v);}}
            if(eat('(')){double v=expr();expect(')');return v;}
            int a=p;boolean dot=false;while(p<s.length()){char c=s.charAt(p);if(Character.isDigit(c)){p++;continue;}if(c=='.'&&!dot){dot=true;p++;continue;}break;}if(a==p)throw new IllegalArgumentException();return Double.parseDouble(s.substring(a,p));
        }
        boolean startsPrimary(){if(p>=s.length())return false;char c=s.charAt(p);return c=='('||c=='.'||Character.isDigit(c)||Character.isLetter(c);}
        boolean eat(char c){if(p<s.length()&&s.charAt(p)==c){p++;return true;}return false;}
        boolean match(String x){if(s.startsWith(x,p)){p+=x.length();return true;}return false;}
        void expect(char c){if(!eat(c))throw new IllegalArgumentException();}
    }
}
