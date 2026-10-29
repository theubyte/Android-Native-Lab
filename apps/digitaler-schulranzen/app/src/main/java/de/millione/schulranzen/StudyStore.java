package de.millione.schulranzen;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.UUID;

/** Lightweight exam planner + spaced repetition store. */
public final class StudyStore {
    public static final class Exam {
        public String id=UUID.randomUUID().toString();
        public String title="Prüfung";
        public String subject="Allgemein";
        public String date=LocalDate.now().plusDays(14).toString();
        public ArrayList<String> sourceIds=new ArrayList<>();
    }
    public static final class Card {
        public String id=UUID.randomUUID().toString();
        public String examId="";
        public String front="";
        public String back="";
        public long dueEpochDay=LocalDate.now().toEpochDay();
        public int reps=0;
        public int interval=0;
        public double ease=2.5;
        public int lastRating=-1;
    }
    public static final class Mark {
        public String id=UUID.randomUUID().toString();
        public String itemId="";
        public int page=0;
        public String itemTitle="";
        public String subject="Allgemein";
        public String label="Für Prüfung merken";
        public float left=-1f,top=-1f,right=-1f,bottom=-1f;
        public long created=System.currentTimeMillis();
    }

    private final SharedPreferences p;
    public StudyStore(Context c){p=c.getSharedPreferences("study_v9",0);}

    public ArrayList<Exam> exams(){ArrayList<Exam> out=new ArrayList<>();try{JSONArray a=new JSONArray(p.getString("exams","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);Exam e=new Exam();e.id=o.optString("id",e.id);e.title=o.optString("title","Prüfung");e.subject=o.optString("subject","Allgemein");e.date=o.optString("date",LocalDate.now().plusDays(14).toString());JSONArray s=o.optJSONArray("sources");if(s!=null)for(int k=0;k<s.length();k++)e.sourceIds.add(s.optString(k));out.add(e);}}catch(Exception ignored){}Collections.sort(out,(a,b)->a.date.compareTo(b.date));return out;}
    public ArrayList<Card> cards(){ArrayList<Card> out=new ArrayList<>();try{JSONArray a=new JSONArray(p.getString("cards","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);Card c=new Card();c.id=o.optString("id",c.id);c.examId=o.optString("examId","");c.front=o.optString("front","");c.back=o.optString("back","");c.dueEpochDay=o.optLong("due",LocalDate.now().toEpochDay());c.reps=o.optInt("reps",0);c.interval=o.optInt("interval",0);c.ease=o.optDouble("ease",2.5);c.lastRating=o.optInt("rating",-1);out.add(c);}}catch(Exception ignored){}return out;}
    public Exam addExam(String title,String subject,String date,ArrayList<String> sourceIds){ArrayList<Exam>a=exams();Exam e=new Exam();e.title=title.trim().isEmpty()?"Prüfung":title.trim();e.subject=subject;e.date=date;e.sourceIds=sourceIds==null?new ArrayList<>():new ArrayList<>(sourceIds);a.add(e);saveExams(a);return e;}
    public void updateExam(Exam e){ArrayList<Exam>a=exams();for(int i=0;i<a.size();i++)if(a.get(i).id.equals(e.id)){a.set(i,e);saveExams(a);return;}}
    public void deleteExam(String id){ArrayList<Exam>a=exams();a.removeIf(e->e.id.equals(id));saveExams(a);ArrayList<Card>c=cards();c.removeIf(x->x.examId.equals(id));saveCards(c);}
    public Card addCard(String examId,String front,String back){ArrayList<Card>a=cards();Card c=new Card();c.examId=examId;c.front=front.trim();c.back=back.trim();a.add(c);saveCards(a);return c;}
    public void deleteCard(String id){ArrayList<Card>a=cards();a.removeIf(c->c.id.equals(id));saveCards(a);}
    public ArrayList<Card> cardsFor(String examId){ArrayList<Card> a=cards();a.removeIf(c->!c.examId.equals(examId));return a;}
    public ArrayList<Card> dueFor(String examId){long today=LocalDate.now().toEpochDay();ArrayList<Card>a=cardsFor(examId);a.removeIf(c->c.dueEpochDay>today);Collections.sort(a,(x,y)->Long.compare(x.dueEpochDay,y.dueEpochDay));return a;}
    public void rate(Card card,int rating,Exam exam){ArrayList<Card>a=cards();for(Card c:a)if(c.id.equals(card.id)){applyRating(c,rating,exam);break;}saveCards(a);}
    private void applyRating(Card c,int rating,Exam exam){rating=Math.max(0,Math.min(3,rating));c.lastRating=rating;if(rating==0){c.reps=0;c.interval=1;c.ease=Math.max(1.3,c.ease-.2);}else{c.reps++;if(c.reps==1)c.interval=rating>=2?1:1;else if(c.reps==2)c.interval=rating==3?4:3;else c.interval=Math.max(1,(int)Math.round(c.interval*c.ease*(rating==1?.75:rating==3?1.25:1.0)));if(rating==1)c.ease=Math.max(1.3,c.ease-.15);if(rating==3)c.ease+=.08;}long due=LocalDate.now().plusDays(c.interval).toEpochDay();if(exam!=null){try{long examDay=LocalDate.parse(exam.date).toEpochDay();due=Math.min(due,Math.max(LocalDate.now().toEpochDay(),examDay-1));}catch(Exception ignored){}}c.dueEpochDay=due;}
    public int masteryPercent(String examId){ArrayList<Card>a=cardsFor(examId);if(a.isEmpty())return 0;int score=0;for(Card c:a){if(c.lastRating<0)score+=0;else if(c.lastRating==0)score+=20;else if(c.lastRating==1)score+=45;else if(c.lastRating==2)score+=75;else score+=100;}return score/a.size();}
    public int recommendedToday(Exam e){int total=cardsFor(e.id).size();if(total==0)return 0;long days=14;try{days=Math.max(1,ChronoUnit.DAYS.between(LocalDate.now(),LocalDate.parse(e.date)));}catch(Exception ignored){}int due=dueFor(e.id).size();int unseen=0;for(Card c:cardsFor(e.id))if(c.reps==0)unseen++;return Math.max(due,(int)Math.ceil((double)unseen/days));}

    public ArrayList<Mark> marks(){ArrayList<Mark> out=new ArrayList<>();try{JSONArray a=new JSONArray(p.getString("marks","[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);Mark m=new Mark();m.id=o.optString("id",m.id);m.itemId=o.optString("itemId","");m.page=Math.max(0,o.optInt("page",0));m.itemTitle=o.optString("itemTitle","");m.subject=o.optString("subject","Allgemein");m.label=o.optString("label","Für Prüfung merken");m.left=(float)o.optDouble("left",-1);m.top=(float)o.optDouble("top",-1);m.right=(float)o.optDouble("right",-1);m.bottom=(float)o.optDouble("bottom",-1);m.created=o.optLong("created",System.currentTimeMillis());out.add(m);}}catch(Exception ignored){}Collections.sort(out,(a,b)->Long.compare(b.created,a.created));return out;}
    public Mark findMark(String itemId,int page){for(Mark m:marks())if(m.itemId.equals(itemId)&&m.page==page)return m;return null;}
    public Mark findPageMark(String itemId,int page){for(Mark m:marks())if(m.itemId.equals(itemId)&&m.page==page&&m.left<0)return m;return null;}
    public boolean isMarked(String itemId,int page){return findMark(itemId,page)!=null;}
    public Mark addMark(String itemId,int page,String itemTitle,String subject,String label){return addMark(itemId,page,itemTitle,subject,label,-1,-1,-1,-1);}
    public Mark addMark(String itemId,int page,String itemTitle,String subject,String label,float left,float top,float right,float bottom){ArrayList<Mark>a=marks();boolean region=left>=0&&top>=0&&right>left&&bottom>top;if(!region)for(Mark m:a)if(m.itemId.equals(itemId)&&m.page==page&&m.left<0){m.itemTitle=itemTitle;m.subject=subject;m.label=label==null||label.trim().isEmpty()?m.label:label.trim();m.created=System.currentTimeMillis();saveMarks(a);return m;}Mark m=new Mark();m.itemId=itemId;m.page=Math.max(0,page);m.itemTitle=itemTitle==null?"":itemTitle;m.subject=subject==null?"Allgemein":subject;m.label=label==null||label.trim().isEmpty()?"Für Prüfung merken":label.trim();if(region){m.left=Math.max(0,Math.min(1,left));m.top=Math.max(0,Math.min(1,top));m.right=Math.max(0,Math.min(1,right));m.bottom=Math.max(0,Math.min(1,bottom));}a.add(m);saveMarks(a);return m;}
    public void removeMark(String id){ArrayList<Mark>a=marks();a.removeIf(m->m.id.equals(id));saveMarks(a);}
    public void toggleMark(String itemId,int page,String itemTitle,String subject){Mark old=findMark(itemId,page);if(old!=null)removeMark(old.id);else addMark(itemId,page,itemTitle,subject,"Für Prüfung merken");}
    private void saveMarks(ArrayList<Mark>a){JSONArray ar=new JSONArray();try{for(Mark m:a){JSONObject o=new JSONObject();o.put("id",m.id);o.put("itemId",m.itemId);o.put("page",m.page);o.put("itemTitle",m.itemTitle);o.put("subject",m.subject);o.put("label",m.label);o.put("left",m.left);o.put("top",m.top);o.put("right",m.right);o.put("bottom",m.bottom);o.put("created",m.created);ar.put(o);}}catch(Exception ignored){}p.edit().putString("marks",ar.toString()).apply();}

    private void saveExams(ArrayList<Exam>a){JSONArray ar=new JSONArray();try{for(Exam e:a){JSONObject o=new JSONObject();o.put("id",e.id);o.put("title",e.title);o.put("subject",e.subject);o.put("date",e.date);JSONArray s=new JSONArray();for(String id:e.sourceIds)s.put(id);o.put("sources",s);ar.put(o);}}catch(Exception ignored){}p.edit().putString("exams",ar.toString()).apply();}
    private void saveCards(ArrayList<Card>a){JSONArray ar=new JSONArray();try{for(Card c:a){JSONObject o=new JSONObject();o.put("id",c.id);o.put("examId",c.examId);o.put("front",c.front);o.put("back",c.back);o.put("due",c.dueEpochDay);o.put("reps",c.reps);o.put("interval",c.interval);o.put("ease",c.ease);o.put("rating",c.lastRating);ar.put(o);}}catch(Exception ignored){}p.edit().putString("cards",ar.toString()).apply();}
}
