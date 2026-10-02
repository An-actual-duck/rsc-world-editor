#!/usr/bin/env python3
"""Inert active registry selection and exact append identity, with no game execution."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
ROOT=Path(__file__).resolve().parents[2]
CLASSES=ROOT/'output/world-builder-tools/classes'
HARNESS='''package com.openrsc.worldbuilder;
import java.nio.file.Paths; import java.util.*;
public class EffectiveSourcesHarness {
 public static void main(String[] args) throws Exception {
  WorldBuilderReadOnlyTarget target=WorldBuilderReadOnlyTarget.open(Paths.get(args[0]));
  WorldBuilderPackedSourceLayout layout=WorldBuilderPackedSourceLayout.canonical("server/myworld.conf");
  WorldBuilderNpcContentSources.Selection selected=WorldBuilderNpcContentSources.inspect(target,layout);
  WorldBuilderSupplementalNpcDefinitions.Result result=WorldBuilderSupplementalNpcDefinitions.normalize(target.root,layout);
  Map<String,Object> out=new TreeMap<>(); out.put("sources",selected.supplemental);out.put("npcs",result.customRows);
  out.put("itemSources",WorldBuilderNpcContentSources.inspectItems(target,layout).supplemental);
  System.out.println(WorldBuilderJsonDocuments.pretty(out));
 }
}'''
LOADER='''class EntityHandler {
 void load() {
  npcs = new ArrayList<>();
  loadNpcs(getServer().getConfig().CONFIG_DIR + "/defs/NpcDefs.json");
  loadNpcs(getServer().getConfig().CONFIG_DIR + "/defs/NpcDefsCustom.json");
  if (getServer().getConfig().WANT_MYWORLD) {
   loadNpcs(getServer().getConfig().CONFIG_DIR + "/defs/ZFirstNpcDefs.json");
   loadNpcs(getServer().getConfig().CONFIG_DIR + "/defs/ASecondNpcDefs.json");
  }
  // loadNpcs(getServer().getConfig().CONFIG_DIR + "/defs/InactiveNpcDefs.json");
  patchNpcs();
 }
 void loadNpcs(String filename) {
  for (int i = 0; i < npcDefs.length(); i++) {
   NPCDef def = new NPCDef(); JSONObject npc = npcDefs.getJSONObject(i);
   def.name = npc.getString("name"); npcs.add(def);
  }
 }
}'''
ITEM_LOADER = '\tprivate void loadItems(String filename) {\n\t\ttry {\n\t\t\tJSONObject object = new JSONObject(new String(Files.readAllBytes(Paths.get(filename))));\n\t\t\tJSONArray itemDefs = object.getJSONArray(JSONObject.getNames(object)[0]);\n\t\t\tfor (int i = 0; i < itemDefs.length(); i++) {\n\t\t\t\tJSONObject item = itemDefs.getJSONObject(i);\n\t\t\t\tItemDefinition toAdd = new ItemDefinition(\n\t\t\t\t\titem.getInt("id"),\n\t\t\t\t\titem.getString("name"),\n\t\t\t\t\titem.getString("description"),\n\t\t\t\t\titem.getString("command").split(","),\n\t\t\t\t\titem.getInt("isFemaleOnly") == 1,\n\t\t\t\t\titem.getInt("isMembersOnly") == 1,\n\t\t\t\t\titem.getInt("isStackable") == 1,\n\t\t\t\t\titem.getInt("isUntradable") == 1,\n\t\t\t\t\titem.getInt("isWearable") == 1,\n\t\t\t\t\titem.getInt("appearanceID"),\n\t\t\t\t\titem.getInt("wearableID"),\n\t\t\t\t\titem.getInt("wearSlot"),\n\t\t\t\t\titem.getInt("requiredLevel"),\n\t\t\t\t\titem.getInt("requiredSkillID"),\n\t\t\t\t\titem.getLong("armourBonus"),\n\t\t\t\t\titem.getInt("weaponAimBonus"),\n\t\t\t\t\titem.getInt("weaponPowerBonus"),\n\t\t\t\t\titem.getInt("magicBonus"),\n\t\t\t\t\titem.getInt("prayerBonus"),\n\t\t\t\t\titem.getInt("basePrice"),\n\t\t\t\t\titem.getInt("isNoteable") == 1\n\t\t\t\t);\n\n\t\t\t\t\tif (toAdd.getCommand().length == 1 && "".equals(toAdd.getCommand()[0])) {\n\t\t\t\t\t\ttoAdd.nullCommand();\n\t\t\t\t\t}\n\t\t\t\t\t// Complete definitions are final values, not partial patches. The\n\t\t\t\t\t// zero sentinel is only for loadPatchItems\' later merge operation.\n\t\t\t\t\tif (item.has("meleeOffense")) toAdd.setMeleeOffense(item.getInt("meleeOffense"));\n\t\t\t\t\tif (item.has("rangedOffense")) toAdd.setRangedOffense(item.getInt("rangedOffense"));\n\t\t\t\t\tif (item.has("magicOffense")) toAdd.setMagicOffense(item.getInt("magicOffense"));\n\t\t\t\t\tif (item.has("weaponSpeed")) toAdd.setWeaponSpeed(item.getInt("weaponSpeed"));\n\t\t\t\t\tif (item.has("meleeDefense")) toAdd.setMeleeDefense(item.getInt("meleeDefense"));\n\t\t\t\t\tif (item.has("rangedDefense")) toAdd.setRangedDefense(item.getInt("rangedDefense"));\n\t\t\t\t\tif (item.has("magicDefense")) toAdd.setMagicDefense(item.getInt("magicDefense"));\n\t\t\t\t\taddItemDefinition(toAdd);\n\t\t\t\t}\n\t\t}\n\t\tcatch (Exception e) {\n\t\t\tLOGGER.error(e);\n\t\t}\n\t}\n\n\tprivate void addItemDefinition(ItemDefinition item) {\n\t\twhile (items.size() <= item.getId()) {\n\t\t\titems.add(null);\n\t\t}\n\t\titems.set(item.getId(), item);\n\t}\n'
def write(path,value):
 path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(value))
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
class EffectiveSourcesTest(unittest.TestCase):
 @classmethod
 def setUpClass(cls):
  subprocess.run([str(ROOT/'scripts/build-tools.sh')],check=True)
  cls.tmp=tempfile.TemporaryDirectory(prefix='effective-sources-harness-');p=Path(cls.tmp.name)
  (p/'EffectiveSourcesHarness.java').write_text(HARNESS);subprocess.run(['javac','-cp',str(CLASSES),'-d',str(p),str(p/'EffectiveSourcesHarness.java')],check=True)
 @classmethod
 def tearDownClass(cls):cls.tmp.cleanup()
 def fixture(self,root):
  defs=root/'server/conf/server/defs'
  for name,rows in [('NpcDefs.json',[{'id':0,'name':'base'}]),('NpcDefsCustom.json',[]),('ZFirstNpcDefs.json',[{'id':1,'name':'first'}]),('ASecondNpcDefs.json',[{'id':2,'name':'second'}]),('InactiveNpcDefs.json',[{'id':99,'name':'inactive'}])]:write(defs/name,{'npcs':rows})
  (root/'server/myworld.conf').write_text('want_myworld: true\n')
  loader=root/'server/src/com/openrsc/server/external/EntityHandler.java';loader.parent.mkdir(parents=True);loader.write_text(LOADER)
  return loader
 def run_selection(self,root,success=True):
  result=subprocess.run(['java','-cp',os.pathsep.join([self.tmp.name,str(CLASSES)]),'com.openrsc.worldbuilder.EffectiveSourcesHarness',str(root)],capture_output=True,text=True)
  self.assertEqual(success,result.returncode==0,result.stderr)
  return json.loads(result.stdout) if success else result.stderr
 def descriptor(self,root):
  names=['NpcDefs.json','NpcDefsCustom.json','ZFirstNpcDefs.json','ASecondNpcDefs.json']
  path=root/'server/conf/world-builder/effective-content-sources-v1.json'
  write(path,{'schemaVersion':1,'manifestType':'world-builder-effective-content-sources','configuration':{'relativePath':'server/myworld.conf','sha256':digest(root/'server/myworld.conf')},'npcRegistry':{'semantics':'openrsc-sequential-append-v1','sources':[{'relativePath':f'server/conf/server/defs/{n}','sha256':digest(root/'server/conf/server/defs'/n)} for n in names]}})
  return path
 def test_source_uses_active_load_order_and_ignores_comment_and_unused_catalog(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);self.fixture(root);before={p:p.read_bytes() for p in root.rglob('*') if p.is_file()}
   result=self.run_selection(root);self.assertEqual(['first','second'],[r['name'] for r in result['npcs']]);self.assertEqual([1,2],[r['id'] for r in result['npcs']]);self.assertEqual(before,{p:p.read_bytes() for p in before})
   (root/'server/myworld.conf').write_text('want_myworld: false\n');self.assertEqual([],self.run_selection(root)['npcs'])
 def test_descriptor_is_data_only_and_preserves_explicit_order(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);loader=self.fixture(root);loader.unlink();self.descriptor(root)
   self.assertEqual(['first','second'],[r['name'] for r in self.run_selection(root)['npcs']])
   alternate=root/'server/other.conf';alternate.write_bytes((root/'server/myworld.conf').read_bytes())
   path=root/'server/conf/world-builder/effective-content-sources-v1.json';doc=json.loads(path.read_text());doc['configuration']['relativePath']='server/other.conf';write(path,doc)
   self.assertIn('different or changed configuration',self.run_selection(root,False))
 def test_stale_descriptor_and_unknown_activation_are_rejected(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);loader=self.fixture(root);desc=self.descriptor(root)
   source=root/'server/conf/server/defs/ZFirstNpcDefs.json';source.write_bytes(source.read_bytes()+b' ')
   self.assertIn('stale',self.run_selection(root,False));desc.unlink();loader.write_text(LOADER.replace('WANT_MYWORLD','SOME_UNKNOWN_FLAG'))
   self.assertIn('unsupported condition',self.run_selection(root,False))
 def test_keyed_item_sources_are_selected_from_loader_and_descriptor(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);loader=self.fixture(root)
   for name,rows in [('ItemDefs.json',[{'id':0,'name':'base item'}]),('ItemDefsCustom.json',[]),('NeutralItemDefs.json',[{'id':17,'name':'unplaced item'},{'id':0,'name':'overridden vanilla item'}])]:write(root/'server/conf/server/defs'/name,{'items':rows})
   code=LOADER.replace('  patchNpcs();','  patchNpcs();\n  loadItems(getServer().getConfig().CONFIG_DIR + "/defs/ItemDefs.json");\n  loadItems(getServer().getConfig().CONFIG_DIR + "/defs/ItemDefsCustom.json");\n  loadItems(getServer().getConfig().CONFIG_DIR + "/defs/NeutralItemDefs.json");')
   code=code[:-1]+ITEM_LOADER+'}'
   loader.write_text(code)
   expected=['server/conf/server/defs/NeutralItemDefs.json'];self.assertEqual(expected,self.run_selection(root)['itemSources'])
   for changed in [code.replace('addItemDefinition(toAdd);','if (enabled) { addItemDefinition(toAdd); }'),code.replace('addItemDefinition(toAdd);','addItemDefinition(toAdd); addItemDefinition(toAdd);'),code.replace('addItemDefinition(toAdd);','toAdd.setId(99); addItemDefinition(toAdd);'),code.replace('item.getInt("id"),','item.getInt("id") + 1,')]:
    loader.write_text(changed);self.run_selection(root,False)
   loader.write_text(code)
   path=self.descriptor(root);document=json.loads(path.read_text());document['itemRegistry']={'semantics':'openrsc-id-overwrite-v1','sources':[{'relativePath':f'server/conf/server/defs/{name}','sha256':digest(root/'server/conf/server/defs'/name)} for name in ['ItemDefs.json','ItemDefsCustom.json','NeutralItemDefs.json']]};write(path,document);loader.unlink()
   self.assertEqual(expected,self.run_selection(root)['itemSources'])
   extra=root/expected[0];extra.write_bytes(extra.read_bytes()+b' ');self.assertIn('stale',self.run_selection(root,False))
 def test_conditional_registry_mutation_and_indirect_loaders_require_export(self):
  for replacement in [LOADER.replace('npcs.add(def);','if (enabled) { npcs.add(def); }'),LOADER.replace('  loadNpcs(getServer().getConfig().CONFIG_DIR + "/defs/NpcDefs.json");','  if (unknownFlag) { loadNpcs(getServer().getConfig().CONFIG_DIR + "/defs/NpcDefs.json"); }'),LOADER[:-1]+' void hidden(){ loadNpcs("elsewhere"); } }']:
   with self.subTest(code=replacement),tempfile.TemporaryDirectory() as tmp:
    root=Path(tmp);loader=self.fixture(root);loader.write_text(replacement);self.run_selection(root,False)
 def test_conflicting_ids_are_not_reassigned_and_unknown_source_is_not_guessed(self):
  with tempfile.TemporaryDirectory() as tmp:
   root=Path(tmp);loader=self.fixture(root);write(root/'server/conf/server/defs/ZFirstNpcDefs.json',{'npcs':[{'id':0,'name':'collision'}]})
   self.assertIn('disagrees with its active append slot',self.run_selection(root,False));loader.unlink();self.assertIn('no verified active load order',self.run_selection(root,False))
if __name__=='__main__':unittest.main()
