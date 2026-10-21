#!/usr/bin/env python3
"""Compile transformed vanilla vertex shaders using glslang.
Usage: python validate_shaders.py minecraft-client.jar /path/to/glslang
Requires PyYAML. Does not validate shaderpack or Veil-generated sources.
"""
from pathlib import Path
import re, subprocess, sys, tempfile, zipfile
import yaml
root=Path(__file__).resolve().parents[2]
configs=yaml.safe_load((root/'src/main/resources/assets/immersive_portals/shaders/shader_transformation.yaml').read_text())['configs']
with zipfile.ZipFile(sys.argv[1]) as archive, tempfile.TemporaryDirectory(prefix='v053-glsl-') as temp:
    tested=0
    for config in configs:
        for name in config['affectedShaders']:
            filename='assets/minecraft/shaders/core/'+name+'.vsh'
            if filename not in archive.namelist(): continue
            included=set()
            def expand(source):
                def include(match):
                    path='assets/minecraft/shaders/include/'+match[1]
                    if path in included: return ''
                    included.add(path)
                    return expand(archive.read(path).decode())
                return re.sub(r'#moj_import\s+<([^>]+)>',include,source)
            source=expand(archive.read(filename).decode())
            # Mojang's preprocessor coalesces the #version declarations from imports.
            versions=re.findall(r'^#version[^\n]*',source,re.M)
            source=versions[0]+'\n'+re.sub(r'^#version[^\n]*\n?', '',source,flags=re.M)
            for change in config['transformations']:
                source=re.sub(change['pattern'],lambda m:change['replacement'],source)
            path=Path(temp)/(name+'.vert');path.write_text(source)
            subprocess.run([sys.argv[2],'-S','vert',str(path)],check=True)
            tested+=1
    assert tested>0, 'No vanilla shaders found in the supplied client jar'
    print('PASS:',tested,'transformed vanilla vertex shaders compiled')
