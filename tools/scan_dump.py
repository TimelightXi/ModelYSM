import re
data = open('dump_xly.txt', encoding='utf-8', errors='replace').read()
groups = re.findall(r'^group=(.+)$', data, re.M)
anims = re.findall(r" anim '([^']+)' ", data)
print('groups:', groups)
print('anims count:', len(anims))
print('anims:', anims[:100])
ctrl = re.findall(r'^ ctrl (.+)$', data, re.M)
print('controllers:', ctrl)
