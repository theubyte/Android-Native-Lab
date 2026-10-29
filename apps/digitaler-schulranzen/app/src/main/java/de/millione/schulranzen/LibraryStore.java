package de.millione.schulranzen;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/** Persistent library/subject model for V9. Keeps UI state small and offline-first. */
public final class LibraryStore {
    public static final String TYPE_NOTEBOOK = "notebook";
    public static final String TYPE_BOOK = "book";
    public static final String TYPE_WORKSHEET = "worksheet";
    public static final String TYPE_TIMETABLE = "timetable";

    public static final class Item {
        public String id = UUID.randomUUID().toString();
        public String type = TYPE_NOTEBOOK;
        public String title = "Unbenannt";
        public String subject = "Allgemein";
        public String uri = "";
        public int color = 0xff3157d5;
        public long created = System.currentTimeMillis();
        public long modified = created;
        public boolean deleted = false;

        Item copy() {
            Item i = new Item();
            i.id=id; i.type=type; i.title=title; i.subject=subject; i.uri=uri; i.color=color;
            i.created=created; i.modified=modified; i.deleted=deleted;
            return i;
        }
    }

    public static final class Subject {
        public String name;
        public int color;
        Subject(String n,int c){name=n;color=c;}
    }

    private static final String PREF = "library_v9";
    private static final String KEY_ITEMS = "items";
    private static final String KEY_SUBJECTS = "subjects";
    private final SharedPreferences p;
    private final Context context;

    public LibraryStore(Context c) {
        context = c.getApplicationContext();
        p = context.getSharedPreferences(PREF, 0);
        ensureSubjects();
        migrateLegacy();
    }

    private void ensureSubjects() {
        if (p.contains(KEY_SUBJECTS)) return;
        ArrayList<Subject> s = new ArrayList<>();
        s.add(new Subject("Allgemein", 0xff6b7280));
        s.add(new Subject("Mathe", 0xff3157d5));
        s.add(new Subject("Deutsch", 0xffd84b5b));
        s.add(new Subject("Englisch", 0xff7b4bc4));
        s.add(new Subject("SHK", 0xff16875b));
        s.add(new Subject("Fachkunde", 0xffd38324));
        saveSubjects(s);
    }

    private void migrateLegacy() {
        if (!loadItems().isEmpty()) return;
        SharedPreferences old = context.getSharedPreferences("bag", 0);
        ArrayList<Item> items = new ArrayList<>();
        Item n = new Item();
        n.id = "legacy-main";
        n.type = TYPE_NOTEBOOK;
        n.title = old.getString("notebook_name", "SHK Fachkunde").replace('\n',' ');
        n.subject = n.title.toLowerCase().contains("shk") ? "SHK" : "Allgemein";
        n.color = subjectColor(n.subject);
        items.add(n);
        String bookUri = old.getString("book_uri", null);
        if (bookUri != null && !bookUri.isEmpty()) {
            Item b = new Item();
            b.id = "legacy-book";
            b.type = TYPE_BOOK;
            b.title = old.getString("book_name", "Schulbuch");
            b.subject = n.subject;
            b.color = 0xff6a4d3b;
            b.uri = bookUri;
            items.add(b);
        }
        saveItems(items);
    }

    public ArrayList<Item> all(boolean includeDeleted) {
        ArrayList<Item> a = loadItems();
        if (!includeDeleted) a.removeIf(i -> i.deleted);
        Collections.sort(a, (x,y) -> Long.compare(y.modified, x.modified));
        return a;
    }

    public Item get(String id) {
        for (Item i : loadItems()) if (i.id.equals(id)) return i;
        return null;
    }

    public Item add(String type, String title, String subject, int color, String uri) {
        ArrayList<Item> a=loadItems();
        Item i=new Item(); i.type=type; i.title=cleanTitle(title); i.subject=cleanSubject(subject); i.color=color;
        i.uri=uri==null?"":uri; i.created=i.modified=System.currentTimeMillis(); a.add(i); saveItems(a); return i;
    }

    public void update(Item item) {
        ArrayList<Item> a=loadItems();
        for (int k=0;k<a.size();k++) if (a.get(k).id.equals(item.id)) { item.modified=System.currentTimeMillis(); a.set(k,item.copy()); saveItems(a); return; }
    }

    public void touch(String id) {
        Item i=get(id); if(i!=null){i.modified=System.currentTimeMillis();update(i);}
    }

    public void trash(String id) { Item i=get(id); if(i!=null){i.deleted=true;update(i);} }
    public void restore(String id) { Item i=get(id); if(i!=null){i.deleted=false;update(i);} }
    public void purge(String id) {
        ArrayList<Item> a=loadItems(); a.removeIf(i -> i.id.equals(id)); saveItems(a);
    }


    /** Relinks restored books to app-owned PDF copies contained in the backup. */
    public synchronized int relinkLocalBooks() {
        ArrayList<Item> items=loadItems();int changed=0;
        for(Item i:items){
            if(!TYPE_BOOK.equals(i.type))continue;
            java.io.File f=AppFileResolver.bookFile(context,i.id);
            if(f.exists()&&f.length()>0){String token=AppFileResolver.bookToken(i.id);if(!token.equals(i.uri)){i.uri=token;i.modified=System.currentTimeMillis();changed++;}}
        }
        if(changed>0)saveItems(items);return changed;
    }

    public ArrayList<Subject> subjects() { return loadSubjects(); }

    public int subjectColor(String name) {
        for (Subject s:loadSubjects()) if(s.name.equalsIgnoreCase(name)) return s.color;
        return 0xff6b7280;
    }

    public void addOrUpdateSubject(String name,int color) {
        name=cleanSubject(name); ArrayList<Subject> a=loadSubjects();
        for(Subject s:a) if(s.name.equalsIgnoreCase(name)){s.name=name;s.color=color;saveSubjects(a);return;}
        a.add(new Subject(name,color)); saveSubjects(a);
    }


    public void renameSubject(String oldName,String newName,int color){
        newName=cleanSubject(newName);ArrayList<Subject> subs=loadSubjects();boolean found=false;for(Subject s:subs)if(s.name.equalsIgnoreCase(oldName)){s.name=newName;s.color=color;found=true;break;}if(!found)subs.add(new Subject(newName,color));saveSubjects(subs);ArrayList<Item> items=loadItems();for(Item i:items)if(i.subject.equalsIgnoreCase(oldName)){i.subject=newName;i.color=color;i.modified=System.currentTimeMillis();}saveItems(items);
    }

    public void removeSubject(String name) {
        if("Allgemein".equalsIgnoreCase(name))return;
        ArrayList<Subject> s=loadSubjects(); s.removeIf(x->x.name.equalsIgnoreCase(name)); saveSubjects(s);
        ArrayList<Item> items=loadItems(); for(Item i:items) if(i.subject.equalsIgnoreCase(name)){i.subject="Allgemein";i.color=subjectColor("Allgemein");} saveItems(items);
    }

    private ArrayList<Item> loadItems() {
        ArrayList<Item> out=new ArrayList<>();
        try {
            JSONArray a=new JSONArray(p.getString(KEY_ITEMS,"[]"));
            for(int k=0;k<a.length();k++){
                JSONObject o=a.getJSONObject(k); Item i=new Item();
                i.id=o.optString("id",UUID.randomUUID().toString()); i.type=o.optString("type",TYPE_NOTEBOOK);
                i.title=o.optString("title","Unbenannt"); i.subject=o.optString("subject","Allgemein"); i.uri=o.optString("uri","");
                i.color=o.optInt("color",0xff3157d5); i.created=o.optLong("created",System.currentTimeMillis()); i.modified=o.optLong("modified",i.created); i.deleted=o.optBoolean("deleted",false);
                out.add(i);
            }
        } catch(Exception ignored){}
        return out;
    }

    private void saveItems(List<Item> items) {
        JSONArray a=new JSONArray();
        try{ for(Item i:items){JSONObject o=new JSONObject();o.put("id",i.id);o.put("type",i.type);o.put("title",i.title);o.put("subject",i.subject);o.put("uri",i.uri);o.put("color",i.color);o.put("created",i.created);o.put("modified",i.modified);o.put("deleted",i.deleted);a.put(o);} }catch(Exception ignored){}
        p.edit().putString(KEY_ITEMS,a.toString()).apply();
    }

    private ArrayList<Subject> loadSubjects(){
        ArrayList<Subject> out=new ArrayList<>();
        try{JSONArray a=new JSONArray(p.getString(KEY_SUBJECTS,"[]"));for(int i=0;i<a.length();i++){JSONObject o=a.getJSONObject(i);out.add(new Subject(o.optString("name","Allgemein"),o.optInt("color",0xff6b7280)));}}catch(Exception ignored){}
        if(out.isEmpty()) out.add(new Subject("Allgemein",0xff6b7280));
        return out;
    }

    private void saveSubjects(List<Subject> subjects){
        JSONArray a=new JSONArray();try{for(Subject s:subjects){JSONObject o=new JSONObject();o.put("name",s.name);o.put("color",s.color);a.put(o);}}catch(Exception ignored){}p.edit().putString(KEY_SUBJECTS,a.toString()).apply();
    }

    private static String cleanTitle(String s){s=s==null?"":s.trim();return s.isEmpty()?"Unbenannt":s;}
    private static String cleanSubject(String s){s=s==null?"":s.trim();return s.isEmpty()?"Allgemein":s;}
}
