#!/usr/bin/env python3
"""Verify packaged 0.6.0 payload; run from project root with the release JAR path."""
import hashlib,json,sys,zipfile
from pathlib import Path
jar=Path(sys.argv[1])
with zipfile.ZipFile(jar) as z:
    names=set(z.namelist());config=json.loads(z.read('ipl_sable.mixins.json'))
    for name in config['mixins']+config['client']:
        assert 'ipl/sable/mixin/'+name.replace('.','/')+'.class' in names,name
    for name in ['IplScaledClipReadViewMixin','IplPlotEntityUnloadGuardMixin','IplOwnedRenderConsumersMixin','IplOwnedSodiumRenderConsumersMixin',
                 'IplBalloonEnvironmentScopeMixin','IplSubLevelSelectorBridgeMixin','IplClientFlywheelReroute','IplHostedEntityVisuals',
                 'IplHostedFlywheelVisualRerouteMixin','IplHostedVisualRemovalMixin','ipl/sable/mixin/IplHostedClientEntityLookupMixin']:
        assert not any(name in entry for entry in names),name
    for name in ['ipl/sable/IplSableRenderOwnershipPlugin.class','ipl/sable/dim/IplHostedEntityOwnership.class','ipl/sable/render/IplSubLevelBlockEntityPresence.class','ipl/sable/dim/IplHostedPlotHeight.class',
                 'ipl/sable/dim/IplProcessingPass.class','ipl/sable/dim/IplPlotEntityStorage.class','ipl/sable/atlas/IplContraptionCharts.class',
                 'ipl/sable/client/IplHostedFlywheelVisuals.class','ipl/sable/client/IplClientSoundFrames.class']:
        assert name in names,name
    assert config['plugin']=='ipl.sable.IplSableRenderOwnershipPlugin'
    assert not any(n.startswith(('com/misterblusky9/pym/','dev/simulated_team/','com/simibubi/create/')) for n in names)
    assert 'version="0.6.0"' in z.read('META-INF/neoforge.mods.toml').decode()
    for native in ['sable_rapier_x86_64_linux.so','sable_rapier_x86_64_windows.dll']:
        assert z.read('natives_ipl/'+native)==Path(native).read_bytes(),native
    assert z.testzip() is None
print('PASS JAR 0.6.0: version, mixin entries, plugin, new helpers, absent deleted hooks/upstream copies, exact natives, CRC')
print('SHA256',hashlib.sha256(jar.read_bytes()).hexdigest(),jar.name)
