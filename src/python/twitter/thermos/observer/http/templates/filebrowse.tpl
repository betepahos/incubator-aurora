<%doc>
 Template arguments:
   data
   uid
   filename
   filelen
   read (bytes read)
   offset
   bytes
   has_more
</%doc>

<%def name="download_link()">
  <a href='/download/${uid}/${filename}'><font size=1>download</font></a>
</%def>

<%def name="less_link()">
  <a href='/file/${uid}/${filename}?offset=${offset-bytes}&bytes=${bytes}'>&#171; prev</a>
</%def>

<%def name="greater_link()">
  <a href='/file/${uid}/${filename}?offset=${offset+bytes}&bytes=${bytes}'>next &#187;</a>
</%def>

<html>

<link rel="stylesheet"
      type="text/css"
      href="/assets/bootstrap.css"/>

<title>file browser ${uid}</title>
<body>
  <div class="span16">
    <strong> path </strong> ${filename}
  </div>

  <div class="span4">
    ${download_link()}
  </div>
  <div class="span4">
    <strong> size </strong> ${filelen}
  </div>
  <div class="span4">
    <strong> bytes </strong> ${offset}...${offset+read}
  </div>
  <div class="span4">
    % if offset > 0:
      ${less_link()}
    % else:
      &#171; prev
    % endif
    % if has_more:
      ${greater_link()}
    % else:
      next &#187;
    % endif
  </div>

  <div class="span16">
<pre>
${data}
</pre>
  </div>

</body>
</html>