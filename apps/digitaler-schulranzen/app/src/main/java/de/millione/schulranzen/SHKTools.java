package de.millione.schulranzen;

import java.util.ArrayList;
import java.util.Locale;

/** Pure SHK calculations and unit conversions. Kept Android-free so it can be tested separately. */
public final class SHKTools {
    private SHKTools() {}

    public static final int FLOW_FROM_POWER = 0;
    public static final int POWER_FROM_FLOW = 1;
    public static final int PIPE_VOLUME = 2;
    public static final int FLOW_VELOCITY = 3;
    public static final int WATER_HEAT_ENERGY = 4;
    public static final int PRESSURE_HEAD = 5;

    public static final class Formula {
        public final String id, category, title, formula, note;
        public final int calculatorMode;
        Formula(String id,String category,String title,String formula,String note,int calculatorMode){
            this.id=id;this.category=category;this.title=title;this.formula=formula;this.note=note;this.calculatorMode=calculatorMode;
        }
    }

    public static ArrayList<Formula> formulas(){
        ArrayList<Formula> out=new ArrayList<>();
        out.add(new Formula("flow","Hydraulik","Volumenstrom aus Heizleistung","V̇ = P / (1,163 · ΔT)","P in kW, V̇ in m³/h, ΔT in K. Näherung für Wasser.",FLOW_FROM_POWER));
        out.add(new Formula("power","Hydraulik","Heizleistung aus Volumenstrom","P = 1,163 · V̇ · ΔT","V̇ in m³/h, ΔT in K, Ergebnis in kW.",POWER_FROM_FLOW));
        out.add(new Formula("pipe_volume","Rohrnetz","Rohrinhalt","V = π · dᵢ² / 4 · L","dᵢ = Innendurchmesser, L = Rohrlänge.",PIPE_VOLUME));
        out.add(new Formula("velocity","Rohrnetz","Fließgeschwindigkeit","v = Q / A","Q in m³/s und A = π · dᵢ² / 4.",FLOW_VELOCITY));
        out.add(new Formula("water_energy","Wärmelehre","Wasser erwärmen","E ≈ V · ΔT · 1,163 / 1000","V in Liter, ΔT in K, Ergebnis in kWh.",WATER_HEAT_ENERGY));
        out.add(new Formula("heat_energy","Wärmelehre","Wärmemenge","Q = m · c · ΔT","Für Wasser: c ≈ 4,19 kJ/(kg·K).",-1));
        out.add(new Formula("pressure_head","Hydraulik","Druck / Förderhöhe","1 bar ≈ 10,197 mWS","Umrechnung zwischen bar und Meter Wassersäule.",PRESSURE_HEAD));
        out.add(new Formula("circle_area","Geometrie","Kreisfläche","A = π · d² / 4","Hilfreich für Rohrquerschnitt und Fließgeschwindigkeit.",-1));
        out.add(new Formula("electric_1p","Elektrotechnik","Elektrische Leistung 1~","P = U · I · cos φ","Bei rein ohmscher Last ist cos φ ≈ 1.",-1));
        out.add(new Formula("electric_3p","Elektrotechnik","Elektrische Leistung 3~","P = √3 · U · I · cos φ","Drehstrom-Wirkleistung.",-1));
        out.add(new Formula("efficiency","Grundlagen","Wirkungsgrad","η = Pₙutz / P₍zu₎","Als Prozent: η · 100.",-1));
        return out;
    }

    public static String[] calcNames(){
        return new String[]{"Volumenstrom aus Leistung","Leistung aus Volumenstrom","Rohrinhalt","Fließgeschwindigkeit","Energie zum Wassererwärmen","Druck ↔ Förderhöhe"};
    }

    public static String[] inputLabels(int mode){
        switch(mode){
            case FLOW_FROM_POWER:return new String[]{"Heizleistung P [kW]","Temperaturspreizung ΔT [K]"};
            case POWER_FROM_FLOW:return new String[]{"Volumenstrom [l/min]","Temperaturspreizung ΔT [K]"};
            case PIPE_VOLUME:return new String[]{"Innendurchmesser dᵢ [mm]","Rohrlänge L [m]"};
            case FLOW_VELOCITY:return new String[]{"Volumenstrom [l/min]","Innendurchmesser dᵢ [mm]"};
            case WATER_HEAT_ENERGY:return new String[]{"Wassermenge [Liter]","Temperaturänderung ΔT [K]"};
            case PRESSURE_HEAD:return new String[]{"Druck [bar]","Förderhöhe [mWS]"};
            default:return new String[]{"Wert 1","Wert 2"};
        }
    }

    public static String calculate(int mode,double a,double b){
        switch(mode){
            case FLOW_FROM_POWER:{
                requirePositive(a,b);double m3h=a/(1.163*b);double lmin=m3h*1000.0/60.0;
                return f(m3h)+" m³/h  ·  "+f(lmin)+" l/min";
            }
            case POWER_FROM_FLOW:{
                requirePositive(a,b);double m3h=a*60.0/1000.0;double kw=1.163*m3h*b;
                return f(kw)+" kW";
            }
            case PIPE_VOLUME:{
                requirePositive(a,b);double d=a/1000.0;double liters=Math.PI*d*d/4.0*b*1000.0;
                return f(liters)+" Liter";
            }
            case FLOW_VELOCITY:{
                requirePositive(a,b);double q=a/1000.0/60.0;double d=b/1000.0;double area=Math.PI*d*d/4.0;double v=q/area;
                return f(v)+" m/s";
            }
            case WATER_HEAT_ENERGY:{
                requirePositive(a,b);double kwh=a*b*1.163/1000.0;
                return f(kwh)+" kWh";
            }
            case PRESSURE_HEAD:{
                if(a>0 && b<=0)return f(a*10.19716213)+" mWS";
                if(b>0 && a<=0)return f(b/10.19716213)+" bar";
                if(a>0 && b>0)return f(a*10.19716213)+" mWS  ·  "+f(b/10.19716213)+" bar";
                throw new IllegalArgumentException("Mindestens einen positiven Wert eingeben");
            }
            default:throw new IllegalArgumentException("Unbekannte Berechnung");
        }
    }

    private static void requirePositive(double... v){for(double x:v)if(!(x>0)||!Double.isFinite(x))throw new IllegalArgumentException("Werte müssen größer als 0 sein");}

    public static String[] categories(){return new String[]{"Druck","Volumenstrom","Leistung","Energie","Länge","Volumen","Temperatur"};}
    public static String[] units(String category){
        switch(category){
            case "Druck":return new String[]{"bar","mbar","kPa","Pa","mWS"};
            case "Volumenstrom":return new String[]{"l/min","l/h","m³/h","m³/s"};
            case "Leistung":return new String[]{"W","kW"};
            case "Energie":return new String[]{"Wh","kWh","MJ"};
            case "Länge":return new String[]{"mm","cm","m"};
            case "Volumen":return new String[]{"ml","l","m³"};
            case "Temperatur":return new String[]{"°C","K","°F"};
            default:return new String[]{"–"};
        }
    }

    public static double convert(String category,String from,String to,double value){
        if(!Double.isFinite(value))throw new IllegalArgumentException("Ungültiger Wert");
        if(from.equals(to))return value;
        if("Temperatur".equals(category)){
            double c;
            if("°C".equals(from))c=value;else if("K".equals(from))c=value-273.15;else if("°F".equals(from))c=(value-32.0)*5.0/9.0;else throw new IllegalArgumentException();
            if("°C".equals(to))return c;if("K".equals(to))return c+273.15;if("°F".equals(to))return c*9.0/5.0+32.0;throw new IllegalArgumentException();
        }
        return fromBase(category,to,toBase(category,from,value));
    }

    private static double toBase(String c,String u,double v){
        switch(c){
            case "Druck":if("Pa".equals(u))return v;if("kPa".equals(u))return v*1000;if("mbar".equals(u))return v*100;if("bar".equals(u))return v*100000;if("mWS".equals(u))return v*9806.65;break;
            case "Volumenstrom":if("m³/s".equals(u))return v;if("m³/h".equals(u))return v/3600.0;if("l/min".equals(u))return v/1000.0/60.0;if("l/h".equals(u))return v/1000.0/3600.0;break;
            case "Leistung":if("W".equals(u))return v;if("kW".equals(u))return v*1000.0;break;
            case "Energie":if("Wh".equals(u))return v;if("kWh".equals(u))return v*1000.0;if("MJ".equals(u))return v*1000000.0/3600.0;break;
            case "Länge":if("m".equals(u))return v;if("cm".equals(u))return v/100.0;if("mm".equals(u))return v/1000.0;break;
            case "Volumen":if("m³".equals(u))return v;if("l".equals(u))return v/1000.0;if("ml".equals(u))return v/1000000.0;break;
        }
        throw new IllegalArgumentException("Unbekannte Einheit");
    }

    private static double fromBase(String c,String u,double v){
        switch(c){
            case "Druck":if("Pa".equals(u))return v;if("kPa".equals(u))return v/1000;if("mbar".equals(u))return v/100;if("bar".equals(u))return v/100000;if("mWS".equals(u))return v/9806.65;break;
            case "Volumenstrom":if("m³/s".equals(u))return v;if("m³/h".equals(u))return v*3600.0;if("l/min".equals(u))return v*1000.0*60.0;if("l/h".equals(u))return v*1000.0*3600.0;break;
            case "Leistung":if("W".equals(u))return v;if("kW".equals(u))return v/1000.0;break;
            case "Energie":if("Wh".equals(u))return v;if("kWh".equals(u))return v/1000.0;if("MJ".equals(u))return v*3600.0/1000000.0;break;
            case "Länge":if("m".equals(u))return v;if("cm".equals(u))return v*100.0;if("mm".equals(u))return v*1000.0;break;
            case "Volumen":if("m³".equals(u))return v;if("l".equals(u))return v*1000.0;if("ml".equals(u))return v*1000000.0;break;
        }
        throw new IllegalArgumentException("Unbekannte Einheit");
    }

    public static String f(double v){
        if(Math.rint(v)==v)return Long.toString((long)v);
        return String.format(Locale.GERMANY,"%.4f",v).replaceAll("0+$","").replaceAll(",?$","");
    }
}
