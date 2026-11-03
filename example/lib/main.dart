import 'dart:async';

import 'package:flutter/material.dart';
import 'package:kiosk_printer/kiosk_printer.dart';

void main() => runApp(const ExampleApp());

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'Thermal printer',
        theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
        home: const PrinterPage(),
      );
}

/// Discover (USB), connect, status, print: the manual hardware checklist's
/// controls. The log shows every result and event as the package returns it.
class PrinterPage extends StatefulWidget {
  const PrinterPage({super.key});

  @override
  State<PrinterPage> createState() => _PrinterPageState();
}

class _PrinterPageState extends State<PrinterPage> {
  final _printer = ThermalPrinter();
  final _host = TextEditingController();
  final _log = <String>[];
  StreamSubscription<PrinterEvent>? _events;

  List<UsbPrinterInfo> _devices = const [];
  UsbPrinterInfo? _selected;
  PaperSize _paper = PaperSize.mm80;
  bool _confirm = true;

  @override
  void initState() {
    super.initState();
    _events = _printer.events.listen((e) => _add('event: ${e.runtimeType}'));
    _scan();
  }

  @override
  void dispose() {
    _events?.cancel();
    _host.dispose();
    super.dispose();
  }

  void _add(String line) {
    if (!mounted) return;
    setState(() => _log.insert(0, line));
  }

  Future<void> _scan() async {
    final devices = await _printer.listUsbPrinters();
    if (!mounted) return;
    setState(() {
      _devices = devices;
      _selected = devices.where((d) => d.looksLikePrinter).firstOrNull ??
          devices.firstOrNull;
    });
    _add('found ${devices.length} USB device(s)');
  }

  Future<void> _connectUsb() async {
    final device = _selected;
    if (device == null) return _add('no USB device selected');
    _add('connect: ${await _printer.connect(device.toTarget())}');
  }

  Future<void> _connectNetwork() async {
    final host = _host.text.trim();
    if (host.isEmpty) return _add('enter a host first');
    _add('connect: ${await _printer.connect(PrinterTarget.network(host))}');
  }

  Receipt _sample() => Receipt(paper: _paper)
    ..text('FLUTTER THERMAL PRINTER',
        align: ReceiptAlign.center, bold: true, big: true)
    ..text('${_paper.widthMm} mm, ${_paper.charsPerLine} characters',
        align: ReceiptAlign.center)
    ..divider()
    ..row(left: 'Samosa', right: '14.29')
    ..row(left: 'Masala chai with extra ginger and cardamom', right: '30.00')
    ..divider()
    ..row(left: 'Total', right: '44.29', bold: true)
    ..text('Rupee sign: ₹ prints as Rs', align: ReceiptAlign.center)
    ..feed(4)
    ..cut();

  Future<void> _printSample() async {
    final result = await _printer.print(_sample(), confirm: _confirm);
    _add('print: $result${result.mayHavePrinted ? ' (check the paper!)' : ''}');
  }

  Future<void> _status() async => _add('status: ${await _printer.queryStatus()}');

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Thermal printer')),
        body: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            Row(children: [
              Expanded(
                child: DropdownButton<UsbPrinterInfo>(
                  isExpanded: true,
                  value: _selected,
                  hint: const Text('No USB device'),
                  items: [
                    for (final d in _devices)
                      DropdownMenuItem(
                        value: d,
                        child: Text(
                          '${d.looksLikePrinter ? '[printer] ' : ''}${d.product ?? d.deviceName}',
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                  ],
                  onChanged: (d) => setState(() => _selected = d),
                ),
              ),
              IconButton(onPressed: _scan, icon: const Icon(Icons.refresh)),
            ]),
            Wrap(spacing: 8, children: [
              FilledButton(onPressed: _connectUsb, child: const Text('Connect USB')),
              OutlinedButton(
                onPressed: () async {
                  final d = _selected;
                  if (d != null) _add('permission asked: ${await _printer.requestUsbPermission(d)}');
                },
                child: const Text('Ask permission'),
              ),
              OutlinedButton(
                onPressed: () async => _add('disconnected: ${await _printer.disconnect()}'),
                child: const Text('Disconnect'),
              ),
            ]),
            const SizedBox(height: 8),
            Row(children: [
              Expanded(
                child: TextField(
                  controller: _host,
                  decoration: const InputDecoration(labelText: 'Network host (port 9100)'),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton(onPressed: _connectNetwork, child: const Text('Connect')),
            ]),
            const Divider(height: 32),
            SegmentedButton<PaperSize>(
              segments: [
                for (final p in PaperSize.values)
                  ButtonSegment(value: p, label: Text('${p.widthMm} mm')),
              ],
              selected: {_paper},
              onSelectionChanged: (s) => setState(() => _paper = s.first),
            ),
            SwitchListTile(
              title: const Text('Wait for the printer to confirm'),
              value: _confirm,
              onChanged: (v) => setState(() => _confirm = v),
            ),
            Wrap(spacing: 8, children: [
              FilledButton(onPressed: _printSample, child: const Text('Print sample')),
              OutlinedButton(onPressed: _status, child: const Text('Status')),
            ]),
            const Divider(height: 32),
            for (final line in _log) Text(line, style: const TextStyle(fontSize: 12)),
          ],
        ),
      );
}
