/** Old vs new post-processing on real takes. args: project.json takesDir outDir */
import tw.rfps.voicestudio.*;
import org.json.*;
import java.io.File;
import java.nio.file.*;
import java.util.*;
public class DspAB{public static void main(String[] a)throws Exception{
 JSONObject p=new JSONObject(new String(Files.readAllBytes(Paths.get(a[0])),"UTF-8"));
 Map<String,JSONObject> roles=new HashMap<>();JSONArray rs=p.getJSONArray("roles");for(int i=0;i<rs.length();i++)roles.put(rs.getJSONObject(i).getString("id"),rs.getJSONObject(i));
 File[] fs=new File(a[1]).listFiles((d,n)->n.matches("\\d+_[a-z_]+\\.wav")&&!n.contains("_child"));Arrays.sort(fs);
 Map<String,List<Double>> f0=new HashMap<>();Map<String,float[]> clean=new HashMap<>();
 for(File f:fs){String role=f.getName().replaceAll("^\\d+_|\\.wav$","");float[] c=Dsp.tidy(Dsp.cleanSpeech(Store.readWav(f),24000,0.3),24000);clean.put(f.getName(),c);
   f0.computeIfAbsent(role,k->new ArrayList<>()).add(Dsp.medianF0(c,24000));}
 new File(a[2]).mkdirs();
 for(File f:fs){String role=f.getName().replaceAll("^\\d+_|\\.wav$","");List<Double> l=f0.get(role);Collections.sort(l);double base=l.get(l.size()/2);
   JSONObject post=roles.get(role).getJSONObject("post");float[] c=clean.get(f.getName());
   long t0=System.nanoTime();float[] o=Store.applyPostV1(c,post,base);long t1=System.nanoTime();float[] n=Store.applyPost(c,post,base);long t2=System.nanoTime();
   String b=f.getName().replace(".wav","");Store.writeWav(new File(a[2]+"/"+b+"_0clean.wav"),c);Store.writeWav(new File(a[2]+"/"+b+"_1old.wav"),o);Store.writeWav(new File(a[2]+"/"+b+"_2new.wav"),n);
   double ratioP=Store.pitchRatio(c,post,base);double fm=post.optDouble("formant",1);
   Store.writeWav(new File(a[2]+"/"+b+"_3pitch.wav"),Voice.finish(Voice.change(c,24000,ratioP,1,1,0),24000,-17,0.89f));
   Store.writeWav(new File(a[2]+"/"+b+"_4form.wav"),Voice.finish(Voice.change(c,24000,1,fm,1,0),24000,-17,0.89f));
   int SR=24000;String o2=a[2]+"/"+b;
   Store.writeWav(new File(o2+"_8revO.wav"),Voice.finish(Dsp.reverb(c,SR,0.35),SR,-17,0.89f));
   Store.writeWav(new File(o2+"_8revN.wav"),Voice.finish(Voice.reverb(c,SR,0.35),SR,-17,0.89f));
   Store.writeWav(new File(o2+"_9echO.wav"),Voice.finish(Dsp.echo(c,SR,180,0.35,0.3),SR,-17,0.89f));
   Store.writeWav(new File(o2+"_9echN.wav"),Voice.finish(Voice.echo(c,SR,180,0.35,0.3),SR,-17,0.89f));
   Store.writeWav(new File(o2+"_AlayO.wav"),Voice.finish(Dsp.layer(c,SR,0.5,0.8,0.3),SR,-17,0.89f));
   {float[] lo=Dsp.fit(Voice.change(c,SR,0.5,0.8,1,0),c.length);float[] z=new float[c.length];for(int i=0;i<z.length;i++)z[i]=c[i]+0.3f*lo[i];
    Store.writeWav(new File(o2+"_AlayN.wav"),Voice.finish(z,SR,-17,0.89f));}
   Store.writeWav(new File(o2+"_BgrlO.wav"),Voice.finish(Dsp.growl(c,SR,0.3),SR,-17,0.89f));
   Store.writeWav(new File(o2+"_BgrlN.wav"),Voice.finish(Voice.change(c,SR,1,1,1,0.3),SR,-17,0.89f));
   Store.writeWav(new File(a[2]+"/"+b+"_7formRS.wav"),Voice.finish(Voice.psola(Voice.resample(c,fm),24000,1/fm,fm,0),24000,-17,0.89f));
   Store.writeWav(new File(a[2]+"/"+b+"_5ident.wav"),Voice.finish(Voice.psola(c,24000,1,1,0),24000,-17,0.89f));
   try(java.io.PrintWriter w=new java.io.PrintWriter(a[2]+"/"+b+".json")){w.print(new JSONObject().put("ratio",ratioP).put("formant",fm).put("speed",post.optDouble("speed",1)).toString());}
   System.out.printf("%s base %.0f  old %.0f ms  new %.0f ms  f0 %.0f -> old %.0f new %.0f  len %.2f/%.2f/%.2f%n",b,base,(t1-t0)/1e6,(t2-t1)/1e6,Dsp.medianF0(c,24000),Dsp.medianF0(o,24000),Dsp.medianF0(n,24000),c.length/24000.,o.length/24000.,n.length/24000.);}
}}
